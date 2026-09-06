package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountService;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import com.bank.platform.ledger.JournalService.Posting;
import com.bank.platform.ledger.TransferDtos.MonthSummary;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>F06 operation identity: every user-submitted funding or transfer must
 * carry an idempotency key scoped to the originator's account. The key names
 * one logical intent, and the row stores a canonical payload hash, so an
 * identical replay returns the original operation and money moves once, while
 * reusing the key for a different intent is a 409 conflict - never a silent
 * replay of older money and never a second posting.
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
  private final JournalService journal;
  private final Clock clock;
  private final LedgerCacheInvalidation invalidation;
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
      JournalService journal,
      Clock clock,
      LedgerCacheInvalidation invalidation,
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
    this.journal = journal;
    this.clock = clock;
    this.invalidation = invalidation;
    this.reviewThreshold = reviewThreshold;
    this.depositMax = depositMax;
  }

  /**
   * Simulated external rail (ATM/teller). Only the owning customer can fund
   * their own account. Caches are invalidated AFTER commit, never before
   * (F07): registering the clear inside the transaction defers it to the
   * after-commit hook, so a rolled-back deposit evicts nothing.
   */
  @Transactional
  public Account deposit(String email, UUID accountId, BigDecimal amount, String idempotencyKey) {
    // Validate the *settled* amount first: values that round to zero at the
    // ledger's 4-decimal scale would violate the DB amount > 0 check and
    // surface as a 500. The key is enforced after - direct service callers
    // testing boundary amounts still see the amount error, not a key error.
    BigDecimal scaled = requireSettleable(amount);
    String key = requireKey(idempotencyKey, "deposit");
    Account account = lockOwned(email, accountId);
    movement.requireActive(account);

    // Replay check before the limit applies: an identical replay returns the
    // account's current state - the original deposit already moved the money.
    String hash = fingerprint(TxKind.DEPOSIT, null, account.getId(), scaled, "USD",
        "Simulated deposit");
    Optional<Transaction> existing = transactions
        .findFirstByIdempotencyKeyAndToAccountIdAndFromAccountIdIsNull(key, account.getId());
    if (existing.isPresent()) {
      if (hash.equals(existing.get().getRequestHash())) {
        return account;
      }
      throw new IdempotencyConflictException(
          "Idempotency key was already used for a different deposit");
    }

    if (scaled.compareTo(depositMax) > 0) {
      throw new TransferValidationException("Deposit exceeds the per-transaction limit");
    }
    Instant now = clock.instant();
    if (account.getType() == AccountType.LOAN) {
      // A deposit into a LOAN is a repayment under the same policy as a
      // transfer credit: capped at the amount owed, interest extinguished
      // before principal (F16). The balance never goes positive.
      movement.creditLoan(account, scaled);
    } else {
      account.setBalance(account.getBalance().add(scaled));
      accounts.save(account);
    }

    Transaction tx = new Transaction();
    tx.setToAccountId(account.getId());
    tx.setAmount(scaled);
    tx.setCurrency("USD");
    tx.setMemo("Simulated deposit");
    tx.setKind(TxKind.DEPOSIT);
    tx.setIdempotencyKey(key);
    tx.setRequestHash(hash);
    tx.setFlagged(scaled.compareTo(reviewThreshold) >= 0);
    // An external-rail deposit requests and settles in the same instant.
    tx.setCreatedAt(now);
    tx.setPostedAt(now);
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException race) {
      // A simultaneous identical deposit won the (to, key) uniqueness race
      // (enforced by a partial index on PostgreSQL - V20). This transaction
      // is aborted, so surface a conflict and let the caller retry the
      // identical request: the replay check then returns the winner's result.
      throw new IdempotencyConflictException(
          "This deposit is already being processed; retry the identical request to fetch it");
    }

    // A posted deposit always moves real money: the customer balance rises and
    // a balancing entry pays for it out of the simulator-funding counteraccount
    // (F15). This runs in the same transaction as the balance update, so a
    // failed journal write rolls the whole deposit back.
    journal.post(JournalKind.DEPOSIT, tx.getId().toString(), now,
        "Deposit " + scaled.toPlainString(),
        Posting.account(account.getId(), scaled),
        Posting.counter(JournalLine.SIMULATOR_FUNDING, scaled.negate()));

    User depositor = userOf(email);
    events.depositPosted(depositor, tx, account);
    invalidation.clearSynchronized("summaries", "public-stats");
    return account;
  }

  /**
   * Transfer entry point. Amounts at or above the review threshold do NOT
   * settle here: a HELD intent is recorded (no money moves) and an operator
   * approves it later under locks in {@link HeldTransferService#settleHeldTransfer}.
   * Smaller transfers settle atomically below - both account rows locked in
   * stable ID order (deadlock-safe), debited and credited in one transaction.
   * An idempotent replay returns the original row: money never moves twice
   * for one key, held or not.
   *
   * <p>The accounts are resolved to IDs only (never loaded as managed
   * entities) before the instant path, so {@link #post} is the first reader
   * of the two rows - move()'s lock must not sit on a stale snapshot (the
   * first-read discipline is documented at {@link LedgerMovementService}).
   * The HELD path loads the entities afterwards, safely: nothing writes
   * those rows in this transaction.
   */
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
    String key = requireKey(idempotencyKey, "transfer");

    User sender = userOf(email);
    // Scalar resolution only - no managed Account enters this transaction yet.
    // A foreign account stays indistinguishable from a missing one (404), so
    // account existence is never disclosed to outsiders (same as before). The
    // owner id returned here is a check, NOT the source id: fromAccountId is.
    UUID fromId = fromAccountId;
    UUID actualOwner = accounts.findOwnerIdById(fromId).orElse(null);
    if (!sender.getId().equals(actualOwner)) {
      throw new AccountNotFoundException(fromId);
    }
    String toIbanClean = toIban.trim().toUpperCase();
    UUID toId = accounts.findIdByIban(toIbanClean)
        .orElseThrow(() -> new AccountNotFoundException(toIbanClean,
            "No account with IBAN " + toIbanClean
                + " exists in this simulator - you can only transfer to accounts opened here."));
    if (fromId.equals(toId)) {
      throw new TransferValidationException("Cannot transfer to the same account");
    }

    String currencyNorm = Currencies.normalize(currency);
    String memoNorm = memo == null ? "" : memo.trim();
    // The canonical intent hash: same key + same intent is an idempotent
    // replay; same key + different intent is a conflict (F06).
    String hash = fingerprint(TxKind.TRANSFER, fromId, toId, scaled, currencyNorm, memoNorm);

    // Keys live in the sender's own namespace (DB unique on from + key), so
    // a foreign key can never surface another user's row - the lookup below
    // simply finds nothing and the request proceeds as its own transfer.
    Optional<Transaction> stored = transactions.findByFromAccountIdAndIdempotencyKey(fromId, key);
    if (stored.isPresent()) {
      Transaction existing = stored.get();
      if (sameIntent(existing, toId, hash)) {
        // An idempotent replay returns the original row, whatever the retried
        // payload says; the row is the authoritative answer and money never
        // moves twice for one key.
        return existing;
      }
      // Our key pointed at a different transfer: never replay it silently.
      throw new IdempotencyConflictException(
          "Idempotency key was already used for a different transfer");
    }

    if (scaled.compareTo(reviewThreshold) >= 0) {
      // HELD intent: no money moves, so loading the entities here is safe -
      // nothing writes these rows later in this transaction.
      Account fromRef = accounts.findById(fromAccountId)
          .orElseThrow(() -> new AccountNotFoundException(fromAccountId));
      Account toRef = accounts.findByIban(toIbanClean)
          .orElseThrow(() -> new AccountNotFoundException(toIbanClean,
              "No account with IBAN " + toIbanClean
                  + " exists in this simulator - you can only transfer to accounts opened here."));
      return heldTransfers.holdForReview(sender, fromRef, toRef, scaled, currencyNorm,
          memoNorm, key, hash);
    }
    return post(sender, fromId, toId, scaled, currencyNorm, memoNorm, key, hash);
  }

  /**
   * Instant settlement for below-threshold transfers. The caller resolved
   * both accounts to IDs only, so move()'s locking read is the first (and
   * only) read of the two rows - the state it locks is the state it loads
   * (first-read discipline, see {@link LedgerMovementService}).
   */
  private Transaction post(User sender, UUID fromId, UUID toId, BigDecimal scaled,
      String currency, String memo, String key, String hash) {
    // The movement core takes the pessimistic write locks (always in ID order
    // so concurrent opposite-direction transfers cannot deadlock) and enforces
    // the affordability rule while both rows are locked.
    LedgerMovementService.Moved moved = movement.move(fromId, toId, scaled);
    Account from = moved.from();
    Account to = moved.to();

    Transaction tx = new Transaction();
    tx.setFromAccountId(from.getId());
    tx.setToAccountId(to.getId());
    tx.setAmount(scaled);
    tx.setCurrency(currency);
    tx.setKind(TxKind.TRANSFER);
    tx.setMemo(memo);
    tx.setIdempotencyKey(key);
    tx.setRequestHash(hash);
    tx.setFlagged(false);
    // Below-threshold transfers settle the instant they are submitted, so the
    // request time and the posting time are one instant.
    Instant now = clock.instant();
    tx.setCreatedAt(now);
    tx.setPostedAt(now);
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException race) {
      // We lost a race on the unique (from, key) pair with a *simultaneous*
      // identical request (the row did not exist when we pre-checked). After a
      // constraint violation this transaction can no longer read or write
      // reliably, so do not query again in here: surface a conflict and let
      // the caller retry the identical request - the pre-check above then
      // returns the winner's original row.
      throw new IdempotencyConflictException(
          "This transfer is already being processed; retry the identical request to fetch it");
    }

    // The movement and its journal posting share one transaction: the two
    // account lines mirror exactly what move() did, so money can never move
    // between accounts without a balancing record of it (F15).
    journal.post(JournalKind.TRANSFER, tx.getId().toString(), now,
        "Transfer " + scaled.toPlainString(),
        Posting.account(from.getId(), scaled.negate()),
        Posting.account(to.getId(), scaled));

    events.transferPosted(sender, tx, from, to);
    invalidation.clearSynchronized("summaries", "public-stats");
    return tx;
  }

  /**
   * Authenticated operation-status lookup (F06): resolves the caller's own
   * operation by its idempotency key. Ownership is originator-scoped - a
   * deposit's key lives on the funded account, a transfer's on the sender's -
   * so probing a key that belongs to someone else simply finds nothing.
   */
  @Transactional(readOnly = true)
  public Optional<Transaction> operationStatus(String email, String key) {
    User owner = userOf(email);
    List<UUID> owned = accounts.findByUserIdOrderByCreatedAtAsc(owner.getId()).stream()
        .map(Account::getId)
        .toList();
    if (owned.isEmpty()) {
      return Optional.empty();
    }
    List<Transaction> hits = transactions.findOperationsByKey(key, owned);
    return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
  }

  /**
   * Reads one of the caller's own transactions by id for a durable receipt
   * (F11). Scoping mirrors {@link #operationStatus}: a caller may fetch a row
   * only if they own one of its legs (from or to account) - a foreign or
   * unknown id is indistinguishable (empty, surfaced as 404). Engine rows
   * journaled against the caller's account stay visible because their
   * customer leg is the caller's own account.
   */
  @Transactional(readOnly = true)
  public Optional<Transaction> transferDetail(String email, UUID id) {
    User owner = userOf(email);
    List<UUID> owned = accounts.findByUserIdOrderByCreatedAtAsc(owner.getId()).stream()
        .map(Account::getId)
        .toList();
    if (owned.isEmpty()) {
      return Optional.empty();
    }
    Transaction tx = transactions.findById(id).orElse(null);
    if (tx == null) {
      return Optional.empty();
    }
    UUID from = tx.getFromAccountId();
    UUID to = tx.getToAccountId();
    boolean ownsALeg = (from != null && owned.contains(from)) || (to != null && owned.contains(to));
    return ownsALeg ? Optional.of(tx) : Optional.empty();
  }

  /**
   * Monthly inflow/outflow (oldest first, zero-filled). Authorization runs
   * HERE, before the cache is consulted - the cached computation below never
   * sees a caller identity, so a cache hit can never leak another user's data.
   */
  @Transactional(readOnly = true)
  public List<MonthSummary> summary(String email, UUID accountId, int months) {
    // Authorization runs HERE, before the cache is consulted - the cached
    // computation below never sees a caller identity, so a cache hit can
    // never leak another user's data. The as-of month anchors the cached
    // window (F07): advancing the business clock across a month end changes
    // the key, so no stale "last month" list can be served for this month.
    accountService.accountDetail(email, accountId);
    return summaries.byAccount(accountId, months, YearMonth.now(clock.withZone(ZoneOffset.UTC)));
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
   * User-submitted financial mutations carry an idempotency key (F06);
   * scheduled/admin operations get an equivalent deterministic identity from
   * their own caller instead.
   */
  private String requireKey(String idempotencyKey, String what) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new TransferValidationException(
          "An Idempotency-Key header is required for every " + what);
    }
    String key = idempotencyKey.trim();
    if (key.length() > 64) {
      throw new TransferValidationException("Idempotency-Key must be at most 64 characters");
    }
    return key;
  }

  /**
   * The canonical intent fingerprint. Every field that distinguishes one
   * operation from another under the same key - source, destination, exact
   * normalized amount, currency and normalized memo - feeds the hash, so a
   * replay that changed any of them is detected as a conflict.
   */
  private String fingerprint(TxKind kind, UUID fromId, UUID toId, BigDecimal scaled,
      String currency, String memo) {
    String canonical = kind + "|" + (fromId == null ? "" : fromId) + "|"
        + (toId == null ? "" : toId) + "|" + scaled.toPlainString() + "|"
        + (currency == null ? "" : currency.trim()) + "|" + (memo == null ? "" : memo.trim());
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  /**
   * A stored keyed row matches the incoming request when it was recorded with
   * the same canonical intent. Rows created before the request-hash column
   * existed (V20) have no hash and fall back to the destination comparison -
   * the only evidence they carry.
   */
  private boolean sameIntent(Transaction existing, UUID toId, String hash) {
    if (existing.getRequestHash() == null) {
      return toId.equals(existing.getToAccountId());
    }
    return hash.equals(existing.getRequestHash());
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
