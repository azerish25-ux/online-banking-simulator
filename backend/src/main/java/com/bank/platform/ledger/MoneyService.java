package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MoneyService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final AuditLogRepository audits;

  public MoneyService(
      UserRepository users,
      AccountRepository accounts,
      TransactionRepository transactions,
      AuditLogRepository audits) {
    this.users = users;
    this.accounts = accounts;
    this.transactions = transactions;
    this.audits = audits;
  }

  @Transactional(readOnly = true)
  public List<Account> myAccounts(String email) {
    return accounts.findByUserId(userOf(email).getId());
  }

  @Transactional(readOnly = true)
  public Account accountDetail(String email, UUID accountId) {
    Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getUserId().equals(userOf(email).getId())) {
      throw new AccessDeniedException("Not your account");
    }
    return account;
  }

  /** Simulated external rail (ATM/teller). Only the owning customer can fund their own account. */
  @Transactional
  public Account deposit(String email, UUID accountId, BigDecimal amount) {
    requirePositive(amount);
    Account account = lockOwned(email, accountId);
    assertActive(account);
    account.setBalance(account.getBalance().add(scaled(amount)));
    accounts.save(account);

    Transaction tx = new Transaction();
    tx.setToAccountId(account.getId());
    tx.setAmount(scaled(amount));
    tx.setCurrency("USD");
    tx.setMemo("Simulated deposit");
    transactions.save(tx);

    audits.save(new AuditLog(userOf(email).getId(), "DEPOSIT_POSTED", "Transaction", tx.getId().toString()));
    return account;
  }

  /**
   * Double-entry style transfer. Locks both account rows in stable ID order
   * (deadlock-safe), debits then credits atomically, and returns the original
   * row when the caller's idempotency key is replayed.
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
    requirePositive(amount);
    if (idempotencyKey != null && !idempotencyKey.isBlank()) {
      var replay = transactions.findByIdempotencyKey(idempotencyKey);
      if (replay.isPresent()) {
        return replay.get();
      }
    }

    User sender = userOf(email);
    Account fromRef = accounts.findById(fromAccountId).orElseThrow(() -> new AccountNotFoundException(fromAccountId));
    if (!fromRef.getUserId().equals(sender.getId())) {
      throw new AccessDeniedException("Not your account");
    }
    Account toRef = accounts.findByIban(toIban.trim().toUpperCase())
        .orElseThrow(() -> new AccountNotFoundException(toIban));
    if (fromRef.getId().equals(toRef.getId())) {
      throw new TransferValidationException("Cannot transfer to the same account");
    }

    // Pessimistic write locks, always in ID order so concurrent opposite-direction
    // transfers cannot deadlock.
    UUID firstId = fromRef.getId().compareTo(toRef.getId()) < 0 ? fromRef.getId() : toRef.getId();
    UUID secondId = firstId.equals(fromRef.getId()) ? toRef.getId() : fromRef.getId();
    Account first = accounts.findByIdForUpdate(firstId).orElseThrow(() -> new AccountNotFoundException(firstId));
    Account second = accounts.findByIdForUpdate(secondId).orElseThrow(() -> new AccountNotFoundException(secondId));
    Account from = first.getId().equals(fromRef.getId()) ? first : second;
    Account to = first.getId().equals(toRef.getId()) ? first : second;

    assertActive(from);
    assertActive(to);
    BigDecimal scaled = scaled(amount);
    if (from.getBalance().compareTo(scaled) < 0) {
      throw new InsufficientFundsException();
    }
    from.setBalance(from.getBalance().subtract(scaled));
    to.setBalance(to.getBalance().add(scaled));
    accounts.save(from);
    accounts.save(to);

    Transaction tx = new Transaction();
    tx.setFromAccountId(from.getId());
    tx.setToAccountId(to.getId());
    tx.setAmount(scaled);
    tx.setCurrency(currency == null || currency.isBlank() ? "USD" : currency.trim().toUpperCase());
    tx.setMemo(memo != null && memo.length() > 140 ? memo.substring(0, 140) : memo);
    tx.setIdempotencyKey(idempotencyKey != null && idempotencyKey.isBlank() ? null : idempotencyKey);
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException concurrentReplay) {
      // Lost a race with an identical key: return the winner's row.
      return transactions.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> concurrentReplay);
    }

    audits.save(new AuditLog(sender.getId(), "TRANSFER_POSTED", "Transaction", tx.getId().toString()));
    return tx;
  }

  /** Monthly inflow/outflow for the last N months (oldest first), zero-filled. */
  @Transactional(readOnly = true)
  public java.util.List<com.bank.platform.ledger.TransferDtos.MonthSummary> summary(String email, UUID accountId, int months) {
    Account account = accountDetail(email, accountId);
    int window = Math.min(Math.max(months, 1), 24);
    java.time.YearMonth current = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
    java.time.Instant since = current.minusMonths(window - 1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
    java.util.Map<java.time.YearMonth, BigDecimal[]> buckets = new java.util.LinkedHashMap<>();
    for (int i = window - 1; i >= 0; i--) {
      buckets.put(current.minusMonths(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
    }
    for (Transaction tx : transactions.findByAccountSince(accountId, since)) {
      java.time.YearMonth key = java.time.YearMonth.from(tx.getCreatedAt().atZone(java.time.ZoneOffset.UTC));
      BigDecimal[] slot = buckets.get(key);
      if (slot == null) {
        continue;
      }
      if (accountId.equals(tx.getToAccountId())) {
        slot[0] = slot[0].add(tx.getAmount());
      }
      if (accountId.equals(tx.getFromAccountId())) {
        slot[1] = slot[1].add(tx.getAmount());
      }
    }
    return buckets.entrySet().stream()
        .map(e -> new com.bank.platform.ledger.TransferDtos.MonthSummary(
            e.getKey().toString(), e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString()))
        .toList();
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  private Account lockOwned(String email, UUID accountId) {
    Account account = accounts.findByIdForUpdate(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getUserId().equals(userOf(email).getId())) {
      throw new AccessDeniedException("Not your account");
    }
    return account;
  }

  private void assertActive(Account account) {
    if (!"ACTIVE".equals(account.getStatus())) {
      throw new TransferValidationException("Account " + account.getIban() + " is not active");
    }
  }

  private void requirePositive(BigDecimal amount) {
    if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
      throw new TransferValidationException("Amount must be positive");
    }
  }

  private BigDecimal scaled(BigDecimal amount) {
    return amount.setScale(4, RoundingMode.HALF_EVEN);
  }
}

