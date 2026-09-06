package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import com.bank.platform.common.Money;
import com.bank.platform.notifications.NotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deterministic, resumable interest (F16).
 *
 * <p><b>Policy.</b> Accrual runs price the COMPLETED prior calendar month:
 * <ul>
 *   <li><b>Savings</b> earn daily interest on each calendar day's closing
 *       balance (actual/365 day count), derived from the account's authoritative
 *       journal postings. A balance held before an account's first posting
 *       contributes nothing (no invented pre-journal history), so a deposit
 *       made on the final day of the month earns that one day - never a full
 *       month. Intermediate arithmetic is high-precision; rounding to the
 *       ledger's 4 decimals happens exactly once, at posting.</li>
 *   <li><b>Loans</b> are charged simple monthly interest on the outstanding
 *       PRINCIPAL (annual rate / 12) - never on the total balance, so interest
 *       never compounds, and never capped at the credit limit, so a maxed loan
 *       is not silently forgiven. Repayments extinguish interest before
 *       principal (see LedgerMovementService).</li>
 * </ul>
 *
 * <p><b>Determinism and resumption.</b> Work is bounded per account, in
 * deterministic id order, each unit in its OWN transaction. The accrual row
 * (unique on account + period, rate-version recorded) is inserted first, so a
 * scheduler run and an operator trigger overlapping on the same account
 * cannot both post it: the loser's unit hits the unique constraint and rolls
 * back. A batch that dies part-way resumes on the next run - committed
 * accounts are skipped, the rest are taken. Both the {@link Scheduled} job
 * and the admin API call this same method; per-unit transactions are managed
 * with a {@link TransactionTemplate}, so self-invocation can never silently
 * bypass a transaction boundary.
 */
@Service
public class InterestService {

  /** Rate version in effect. Bumping it records a new basis in accrual rows. */
  static final int RATE_VERSION = 1;

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final JournalService journal;
  private final JournalLineRepository journalLines;
  private final InterestAccrualRepository accruals;
  private final AuditLogRepository audits;
  private final UserRepository users;
  private final NotificationService notifications;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final LedgerCacheInvalidation invalidation;
  private final BigDecimal savingsAnnualRate;
  private final BigDecimal loanAnnualRate;

  public InterestService(
      AccountRepository accounts,
      TransactionRepository transactions,
      JournalService journal,
      JournalLineRepository journalLines,
      InterestAccrualRepository accruals,
      AuditLogRepository audits,
      UserRepository users,
      NotificationService notifications,
      Clock clock,
      TransactionTemplate tx,
      LedgerCacheInvalidation invalidation,
      @Value("${app.interest.savings-annual-rate:0.04}") BigDecimal savingsAnnualRate,
      @Value("${app.interest.loan-annual-rate:0.12}") BigDecimal loanAnnualRate) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.journal = journal;
    this.journalLines = journalLines;
    this.accruals = accruals;
    this.audits = audits;
    this.users = users;
    this.notifications = notifications;
    this.clock = clock;
    this.tx = tx;
    this.invalidation = invalidation;
    this.savingsAnnualRate = savingsAnnualRate;
    this.loanAnnualRate = loanAnnualRate;
  }

  /** Runs at 03:00 on the first of every month. Also triggerable via the admin API for demos. */
  @Scheduled(cron = "0 0 3 1 * *")
  public Map<String, Integer> accrueMonthly() {
    try {
      return accrueMonthlyInternal();
    } finally {
      // Interest postings are per-account transactions; whichever units
      // committed (or none) have changed summary buckets, so the cache is
      // cleared after the run - including a run that died part-way (F07).
      invalidation.clearNow("summaries");
    }
  }

  private Map<String, Integer> accrueMonthlyInternal() {
    // The month that just ended, anchored on the business clock.
    YearMonth period = YearMonth.now(clock.withZone(ZoneOffset.UTC)).minusMonths(1);
    List<Account> candidates = accounts.findAccrualCandidates(
        List.of(AccountType.SAVINGS, AccountType.LOAN), AccountStatus.ACTIVE);

    int accrued = 0;
    for (Account candidate : candidates) {
      // One account = one bounded unit of work in its own transaction: a
      // failure (or a lost uniqueness race) rolls back only this account and
      // the batch resumes on the next run.
      try {
        Integer posted = tx.execute(status -> accrueAccount(candidate.getId(), period));
        accrued += posted == null ? 0 : posted;
      } catch (DataIntegrityViolationException alreadyPosted) {
        // Another run posted this account's period while we waited for the
        // row lock - the unit rolled back cleanly; nothing to do.
      }
    }
    return Map.of("accrued", accrued);
  }

  /**
   * Prices and posts one account's accrual for {@code period}, inside its own
   * transaction. The accrual row is inserted FIRST so the database uniqueness
   * is what decides the winner under overlap; everything else (journal,
   * balance projection, transaction row, audit, notification) commits with it
   * atomically or not at all.
   */
  private Integer accrueAccount(UUID accountId, YearMonth period) {
    // The pessimistic lock makes accrual and a transfer on the same account
    // serialize (transfer-versus-accrual locking) and serializes two accrual
    // runs on the same account.
    Account account = accounts.findByIdForUpdate(accountId).orElse(null);
    if (account == null
        || (account.getType() != AccountType.SAVINGS && account.getType() != AccountType.LOAN)
        || account.getStatus() != AccountStatus.ACTIVE) {
      return 0;
    }
    String periodKey = period.toString();
    if (accruals.existsByAccountIdAndPeriod(accountId, periodKey)) {
      return 0;
    }

    Instant windowStart = period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant windowEnd = period.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

    if (account.getType() == AccountType.SAVINGS) {
      return accrueSavings(account, periodKey, windowStart, windowEnd);
    }
    return accrueLoan(account, periodKey);
  }

  private Integer accrueSavings(Account account, String periodKey, Instant windowStart,
      Instant windowEnd) {
    // All postings since the window opened - the current balance minus their
    // sum is the balance at the window's opening, because every movement is
    // journaled. Days before the account's first posting in the window close
    // at zero (nothing before an account had journaled history is invented).
    List<JournalLine> postings = journalLines
        .findByAccountIdAndPostedAtGreaterThanEqualOrderByPostedAtAsc(account.getId(), windowStart);
    BigDecimal sinceWindow = postings.stream()
        .map(JournalLine::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal opening = account.getBalance().subtract(sinceWindow);

    BigDecimal dailyRate = savingsAnnualRate
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    BigDecimal accrued = BigDecimal.ZERO;
    BigDecimal eligibleTotal = BigDecimal.ZERO;
    int eligibleDays = 0;
    int lineIndex = 0;
    BigDecimal running = opening;
    Instant day = windowStart;
    while (day.isBefore(windowEnd)) {
      Instant nextDay = day.plusSeconds(86_400);
      // Fold in every posting that landed during this UTC day (the list is
      // sorted, and all earlier days are already consumed).
      while (lineIndex < postings.size()
          && postings.get(lineIndex).getPostedAt().isBefore(nextDay)) {
        running = running.add(postings.get(lineIndex).getAmount());
        lineIndex++;
      }
      if (running.signum() > 0) {
        accrued = accrued.add(running.multiply(dailyRate));
        eligibleTotal = eligibleTotal.add(running);
        eligibleDays++;
      }
      day = nextDay;
    }
    BigDecimal posted = accrued.setScale(4, RoundingMode.HALF_EVEN);
    if (posted.signum() == 0) {
      return 0;
    }
    BigDecimal basis = eligibleDays == 0 ? BigDecimal.ZERO
        : eligibleTotal.divide(BigDecimal.valueOf(eligibleDays), 4, RoundingMode.HALF_EVEN);
    return post(account, periodKey, "Savings interest " + periodKey, true, posted, basis,
        eligibleDays);
  }

  private Integer accrueLoan(Account account, String periodKey) {
    BigDecimal principal = account.getPrincipal();
    if (principal.signum() == 0) {
      return 0;
    }
    // Simple monthly interest on outstanding principal only: never on the
    // total balance (no compounding) and never capped at the credit limit
    // (a maxed loan is charged, not forgiven).
    BigDecimal monthlyRate = loanAnnualRate
        .divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN);
    BigDecimal posted = principal.multiply(monthlyRate).setScale(4, RoundingMode.HALF_EVEN);
    if (posted.signum() == 0) {
      return 0;
    }
    return post(account, periodKey, "Loan interest " + periodKey, false, posted, principal, 0);
  }

  /**
   * Commits the accrual atomically: accrual row, balance projection, journal
   * posting (balanced against the INTEREST counteraccount), the customer's
   * INTEREST transaction row, audit and notification. {@code credit} is true
   * for savings (the balance rises) and false for loans (the debt deepens;
   * principal is untouched).
   */
  private Integer post(Account account, String periodKey, String memo, boolean credit,
      BigDecimal amount, BigDecimal basis, int dayCount) {
    Instant now = clock.instant();
    // Insert the accrual row first and flush: its (account, period)
    // uniqueness is the arbitration point for overlapping runs.
    accruals.saveAndFlush(new InterestAccrual(account.getId(), periodKey, RATE_VERSION,
        amount, basis, dayCount, now));

    BigDecimal delta = credit ? amount : amount.negate();
    account.setBalance(account.getBalance().add(delta).setScale(4, RoundingMode.HALF_EVEN));
    account.setLastInterestAt(now);
    accounts.save(account);

    JournalKind kind = JournalKind.INTEREST;
    if (credit) {
      journal.post(kind, account.getId() + ":" + periodKey, now, memo,
          JournalService.Posting.account(account.getId(), amount),
          JournalService.Posting.counter(JournalLine.INTEREST, amount.negate()));
    } else {
      journal.post(kind, account.getId() + ":" + periodKey, now, memo,
          JournalService.Posting.account(account.getId(), amount.negate()),
          JournalService.Posting.counter(JournalLine.INTEREST, amount));
    }

    Transaction tx = new Transaction();
    if (credit) {
      tx.setToAccountId(account.getId());
    } else {
      tx.setFromAccountId(account.getId());
    }
    tx.setAmount(amount);
    tx.setCurrency("USD");
    tx.setKind(TxKind.INTEREST);
    tx.setMemo(memo);
    tx.setCreatedAt(now);
    tx.setPostedAt(now);
    transactions.save(tx);
    audits.save(AuditLog.of(account.getUserId(), "INTEREST_POSTED", "Transaction", tx.getId().toString(),
        "amount", amount.toPlainString(), "account", account.getIban(), "period", periodKey));
    users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(
        owner.getId(), owner.getEmail(), "INTEREST_POSTED", "Monthly interest posted",
        (credit ? "Earned " : "Charged ")
            + Money.usd(amount) + " on account " + account.getIban() + "."));
    return 1;
  }
}
