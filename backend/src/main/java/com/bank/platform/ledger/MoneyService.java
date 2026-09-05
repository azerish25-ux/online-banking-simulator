package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountService;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.TransferDtos.MonthSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-facing money entry point: validates a request, decides which flow
 * it belongs to, and orchestrates. The physical balance movement (locks,
 * affordability) lives in {@link LedgerMovementService}, the HELD-transfer
 * lifecycle in {@link HeldTransferService}, and every audit/notification
 * side effect in {@link LedgerEventsService} - so a change to one behavior
 * lands in one place instead of a single ~430-line god service.
 */
@Service
public class MoneyService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AccountService accountService;
  private final TransactionRepository transactions;
  private final MonthlySummaryCache summaries;
  private final HeldTransferService heldTransfers;
  private final LedgerMovementService movement;
  private final LedgerEventsService events;
  private final BigDecimal reviewThreshold;
  private final BigDecimal depositMax;

  public MoneyService(
      UserRepository users,
      AccountRepository accounts,
      AccountService accountService,
      TransactionRepository transactions,
      MonthlySummaryCache summaries,
      HeldTransferService heldTransfers,
      LedgerMovementService movement,
      LedgerEventsService events,
      @Value("${app.review.large-transfer-threshold:10000}") BigDecimal reviewThreshold,
      @Value("${app.deposit.max-amount:100000}") BigDecimal depositMax) {
    this.users = users;
    this.accounts = accounts;
    this.accountService = accountService;
    this.transactions = transactions;
    this.summaries = summaries;
    this.heldTransfers = heldTransfers;
    this.movement = movement;
    this.events = events;
    this.reviewThreshold = reviewThreshold;
    this.depositMax = depositMax;
  }

  /** Simulated external rail (ATM/teller). Only the owning customer can fund their own account. */
  @CacheEvict(value = {"summaries", "public-stats"}, allEntries = true)
  @Transactional
  public Account deposit(String email, UUID accountId, BigDecimal amount) {
    // Validate the *settled* amount: values that round to zero at the ledger's
    // 4-decimal scale would violate the DB amount > 0 check and surface as a 500.
    BigDecimal scaled = requireSettleable(amount);
    if (scaled.compareTo(depositMax) > 0) {
      throw new TransferValidationException("Deposit exceeds the per-transaction limit");
    }
    Account account = lockOwned(email, accountId);
    movement.requireActive(account);
    account.setBalance(account.getBalance().add(scaled));
    accounts.save(account);

    Transaction tx = new Transaction();
    tx.setToAccountId(account.getId());
    tx.setAmount(scaled);
    tx.setCurrency("USD");
    tx.setMemo("Simulated deposit");
    tx.setKind(TxKind.DEPOSIT);
    tx.setFlagged(scaled.compareTo(reviewThreshold) >= 0);
    transactions.save(tx);

    User depositor = userOf(email);
    events.depositPosted(depositor, tx, account);
    return account;
  }

  /**
   * Transfer entry point. Amounts at or above the review threshold do NOT
   * settle here: a HELD row is recorded (no money moves) and an operator must
   * approve it, which settles under locks in {@link HeldTransferService#settleHeldTransfer}.
   * Smaller transfers settle atomically below: both account rows are locked in
   * stable ID order (deadlock-safe), debited and credited, all in one
   * transaction. An idempotent replay returns the original row - money never
   * moves twice for one key, held or not.
   */
  @CacheEvict(value = {"summaries", "public-stats"}, allEntries = true)
  @Transactional
  public Transaction transfer(
      String email,
      UUID fromAccountId,
      String toIban,
      BigDecimal amount,
      String currency,
      String memo,
      String idempotencyKey) {
    BigDecimal scaled = requireSettleable(amount);

    User sender = userOf(email);
    Account fromRef = accounts.findById(fromAccountId).orElseThrow(() -> new AccountNotFoundException(fromAccountId));
    if (!fromRef.getUserId().equals(sender.getId())) {
      throw new AccountNotFoundException(fromAccountId);
    }
    Account toRef = accounts.findByIban(toIban.trim().toUpperCase())
        .orElseThrow(() -> new AccountNotFoundException(toIban));
    if (fromRef.getId().equals(toRef.getId())) {
      throw new TransferValidationException("Cannot transfer to the same account");
    }

    boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();
    String cleanKey = keyed ? idempotencyKey.trim() : null;
    if (keyed) {
      // Keys live in the sender's own namespace (DB unique on from + key), so
      // a foreign key can never surface another user's row - the lookup below
      // simply finds nothing and the request proceeds as its own transfer.
      Optional<Transaction> stored = transactions.findByFromAccountIdAndIdempotencyKey(fromRef.getId(), cleanKey);
      if (stored.isPresent()) {
        Transaction existing = stored.get();
        // The key identifies the logical transfer: same originator and same
        // destination → an idempotent replay returns the original row,
        // whatever the retried payload says. The row's amount is the source
        // of truth - money never moves twice for one key.
        if (toRef.getId().equals(existing.getToAccountId())) {
          return existing;
        }
        // Our key pointed at a different transfer: never replay it silently.
        throw new TransferValidationException(
            "Idempotency key was already used for a different transfer");
      }
    }

    if (scaled.compareTo(reviewThreshold) >= 0) {
      return heldTransfers.holdForReview(sender, fromRef, toRef, scaled, currency, memo, cleanKey, keyed);
    }
    return post(sender, fromRef, toRef, scaled, currency, memo, cleanKey, keyed);
  }

  /** Instant settlement path for transfers below the review threshold. */
  private Transaction post(User sender, Account fromRef, Account toRef, BigDecimal scaled,
      String currency, String memo, String cleanKey, boolean keyed) {
    // The movement core takes the pessimistic write locks (always in ID order
    // so concurrent opposite-direction transfers cannot deadlock) and enforces
    // the affordability rule while both rows are locked.
    LedgerMovementService.Moved moved = movement.move(fromRef.getId(), toRef.getId(), scaled);
    Account from = moved.from();
    Account to = moved.to();

    Transaction tx = new Transaction();
    tx.setFromAccountId(from.getId());
    tx.setToAccountId(to.getId());
    tx.setAmount(scaled);
    tx.setCurrency(Currencies.normalize(currency));
    tx.setKind(TxKind.TRANSFER);
    tx.setMemo(memo);
    tx.setIdempotencyKey(cleanKey);
    tx.setFlagged(false);
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException race) {
      // We lost a race on the unique (from, key) pair with a *simultaneous*
      // identical request (the row did not exist when we pre-checked). After a
      // constraint violation this transaction can no longer read or write
      // reliably, so do not query again in here: surface a conflict and let
      // the caller retry the identical request - the pre-check above then
      // returns the winner's original row. Without a key there is nothing to
      // deduplicate on, so surface the real failure.
      if (!keyed) {
        throw race;
      }
      throw new TransferValidationException(
          "This transfer is already being processed; retry the identical request to fetch it");
    }

    events.transferPosted(sender, tx, from, to);
    return tx;
  }

  /**
   * Monthly inflow/outflow (oldest first, zero-filled). Authorization runs
   * HERE, before the cache is consulted - the cached computation below never
   * sees a caller identity, so a cache hit can never leak another user's data.
   */
  @Transactional(readOnly = true)
  public List<MonthSummary> summary(String email, UUID accountId, int months) {
    accountService.accountDetail(email, accountId);
    return summaries.byAccount(accountId, months);
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private Account lockOwned(String email, UUID accountId) {
    Account account = accounts.findByIdForUpdate(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getUserId().equals(userOf(email).getId())) {
      throw new AccountNotFoundException(accountId);
    }
    return account;
  }

  /**
   * Validates and scales an incoming amount. The ledger keeps 4 decimals, so
   * an amount that rounds to zero at that scale (e.g. 0.0004) is rejected
   * up front instead of 500-ing on the DB amount > 0 constraint later.
   */
  private BigDecimal requireSettleable(BigDecimal amount) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new TransferValidationException("Amount must be positive");
    }
    BigDecimal scaled = amount.setScale(4, RoundingMode.HALF_EVEN);
    if (scaled.compareTo(BigDecimal.ZERO) <= 0) {
      throw new TransferValidationException("Amount is below the smallest unit (0.0001)");
    }
    return scaled;
  }
}
