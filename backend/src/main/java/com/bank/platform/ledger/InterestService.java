package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountStatusChange;
import com.bank.platform.accounts.AccountStatusChangeRepository;
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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
 * Deterministic, resumable interest on HISTORICAL balances (daily-policy
 * rewrite). This is the simulator's documented accrual policy:
 *
 * <ul>
 *   <li><b>Fixed-365 daily policy for both savings and loans.</b> Each
 *       eligible calendar day contributes its closing balance/principal times
 *       the applicable annual rate / 365. Intermediate arithmetic is
 *       high-precision; rounding to the ledger's 4 decimals happens exactly
 *       once, when posting (HALF_EVEN). The business zone is the configured
 *       {@code app.interest.business-zone} (default UTC) and days close in
 *       that zone.</li>
 *   <li><b>Savings</b> close each day on the balance derived from the
 *       account's authoritative journal postings (deposits/transfers/interest
 *       credits), so nothing is invented before an account had history.</li>
 *   <li><b>Loans price PRINCIPAL ONLY</b>: interest never compounds. Each
 *       day's closing principal is reconstructed from the immutable
 *       {@link PrincipalMovement} history (draws up, principal repayments
 *       down, legacy cutover baseline), never from today's total debt and   *       never from today's principal. A later repayment cannot erase an
   *       earlier period's obligation and a later draw cannot create one. A
   *       maxed loan is charged, not forgiven.</li>
   *   <li><b>Frozen days never accrue.</b> Every FROZEN/ACTIVE transition is
   *       recorded (V27) in the same transaction as the status change, and
   *       each day is eligible only if the account was ACTIVE at its close. A
   *       run skips an account frozen at run time; when it is re-activated the
   *       catch-up prices only the ACTIVE days of each skipped period from
   *       that history: a frozen period is never retroactively charged.</li>
 *   <li><b>Historical periods are priced from the movements that actually
 *       occurred in them.</b> Days before the V26 cutover boundary of a
 *       legacy loan carry no record, so they are never priced and never
 *       claimed as zero: the first supported accrual period starts at the
 *       cutover month.</li>
 *   <li><b>Resumable account-period work.</b> Every eligible account has a
 *       bounded queue of due periods: from its first supported accrual
 *       month (or one past its last completed accrual) through the previous
 *       calendar month. Periods are processed in chronological order, one
 *       account×period unit per transaction, and a legitimately-zero period
 *       is recorded as a completed accrual row so an interrupted batch
 *       resumes exactly where it stopped without re-arguing zero months.</li>
 *   <li><b>Exact duplicate arbitration.</b> The database uniqueness on
 *       (account, period) decides which overlapping scheduler/admin run owns
 *       a unit; a DataIntegrityViolationException is treated as the expected
 *       duplicate only when it names that constraint: any other integrity
 *       failure (foreign key, malformed row) surfaces redacted instead of
 *       being silently swallowed as "already posted".</li>
 * </ul>
 */
@Service
public class InterestService {

  /** Rate/policy version in effect. Bumped when the pricing basis changed. */
  static final int RATE_VERSION = 2;

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final JournalService journal;
  private final JournalLineRepository journalLines;
  private final InterestAccrualRepository accruals;
  private final PrincipalMovementRepository principalMovements;
  private final AccountStatusChangeRepository statusHistory;
  private final AuditLogRepository audits;
  private final UserRepository users;
  private final NotificationService notifications;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final LedgerCacheInvalidation invalidation;
  private final BigDecimal savingsAnnualRate;
  private final BigDecimal loanAnnualRate;
  private final ZoneId businessZone;

  public InterestService(
      AccountRepository accounts,
      TransactionRepository transactions,
      JournalService journal,
      JournalLineRepository journalLines,
      InterestAccrualRepository accruals,
      PrincipalMovementRepository principalMovements,
      AccountStatusChangeRepository statusHistory,
      AuditLogRepository audits,
      UserRepository users,
      NotificationService notifications,
      Clock clock,
      TransactionTemplate tx,
      LedgerCacheInvalidation invalidation,
      @Value("${app.interest.savings-annual-rate:0.04}") BigDecimal savingsAnnualRate,
      @Value("${app.interest.loan-annual-rate:0.12}") BigDecimal loanAnnualRate,
      @Value("${app.interest.business-zone:UTC}") String businessZone) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.journal = journal;
    this.journalLines = journalLines;
    this.accruals = accruals;
    this.principalMovements = principalMovements;
    this.statusHistory = statusHistory;
    this.audits = audits;
    this.users = users;
    this.notifications = notifications;
    this.clock = clock;
    this.tx = tx;
    this.invalidation = invalidation;
    this.savingsAnnualRate = savingsAnnualRate;
    this.loanAnnualRate = loanAnnualRate;
    this.businessZone = ZoneId.of(businessZone);
  }

  /** Runs at 03:00 on the first of every month, in the business zone. Also triggerable via the admin API for demos. */
  @Scheduled(cron = "0 0 3 1 * *", zone = "${app.interest.business-zone:UTC}")
  public Map<String, Integer> accrueMonthly() {
    try {
      return accrueMonthlyInternal();
    } finally {
      // Interest postings are per-account transactions; whichever units
      // committed (or none) have changed summary buckets, so the cache is
      // cleared after the run: including a run that died part-way.
      invalidation.clearNow("summaries");
    }
  }

  private Map<String, Integer> accrueMonthlyInternal() {
    YearMonth latestDue = YearMonth.now(clock.withZone(businessZone)).minusMonths(1);
    List<Account> candidates = accounts.findAccrualCandidates(
        List.of(AccountType.SAVINGS, AccountType.LOAN), AccountStatus.ACTIVE);

    int accrued = 0;
    int completedZero = 0;
    for (Account candidate : candidates) {
      YearMonth start = dueWindowStart(candidate);
      if (start == null || start.isAfter(latestDue)) {
        continue;
      }
      // One account = a sequence of bounded units, each in its OWN
      // transaction, processed chronologically: a failure (or a lost
      // uniqueness race) rolls back only this unit and the batch resumes
      // from the exact missing period on the next run.
      for (YearMonth period = start; !period.isAfter(latestDue); period = period.plusMonths(1)) {
        final YearMonth unit = period;
        try {
          Integer posted = tx.execute(status -> accrueAccount(candidate.getId(), unit));
          if (posted != null && posted == 1) {
            accrued++;
          } else if (posted != null && posted == -1) {
            completedZero++;
          }
        } catch (DataIntegrityViolationException integrity) {
          if (!isExpectedDuplicate(integrity)) {
            // Not the (account, period) uniqueness: a broken foreign key or
            // malformed data must never be swallowed as "already posted".
            throw new IllegalStateException(
                "Interest accrual failed for account " + candidate.getId()
                    + " period " + period + " (integrity, not a duplicate)", integrity);
          }
          // The expected duplicate: another run posted this unit while we
          // waited: it rolled back cleanly and is recorded there.
        }
      }
    }
    return Map.of("accrued", accrued, "completedZero", completedZero);
  }

  /**
   * The first still-due period for an account: one past its last completed
   * accrual, but never before its first SUPPORTED accrual month. For legacy
   * loans (a V26 CUTOVER baseline exists) the first supported month is the
   * cutover month: pre-cutover principal was never recorded, so those months
   * are neither priced nor claimed zero. Everything else starts at the
   * account's creation month.
   */
  private YearMonth dueWindowStart(Account account) {
    final YearMonth supported;
    if (account.getType() == AccountType.LOAN
        && principalMovements.existsByAccountIdAndKind(account.getId(), PrincipalKind.CUTOVER)) {
      supported = principalMovements.earliestPostedAt(account.getId())
          .map(at -> YearMonth.from(at.atZone(businessZone)))
          .orElse(YearMonth.from(account.getCreatedAt().atZone(businessZone)));
    } else {
      supported = YearMonth.from(account.getCreatedAt().atZone(businessZone));
    }
    return accruals.findTopByAccountIdOrderByPeriodDesc(account.getId())
        .map(last -> {
          YearMonth afterLast = YearMonth.parse(last.getPeriod()).plusMonths(1);
          return afterLast.isAfter(supported) ? afterLast : supported;
        })
        .orElse(supported);
  }

  /**
   * Prices and posts one account's accrual for {@code period}, inside its own
   * transaction. The accrual row is inserted FIRST so the database uniqueness
   * is what decides the winner under overlap; everything else (journal,
   * balance projection, transaction row, audit, notification) commits with it
   * atomically or not at all. A legitimately zero period still gets its row
   * (completed-zero), so resumption never re-argues it.
   */
  private Integer accrueAccount(UUID accountId, YearMonth period) {
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
    Instant windowStart = period.atDay(1).atStartOfDay(businessZone).toInstant();
    Instant windowEnd = period.plusMonths(1).atDay(1).atStartOfDay(businessZone).toInstant();

    if (account.getType() == AccountType.SAVINGS) {
      return accrueSavings(account, periodKey, windowStart, windowEnd);
    }
    return accrueLoan(account, periodKey, windowStart, windowEnd);
  }

  private Integer accrueSavings(Account account, String periodKey, Instant windowStart,
      Instant windowEnd) {
    // The opening balance is derived from the authoritative journal: today's
    // projected balance minus the signed total of every posting AT OR AFTER
    // the window start (deliberately unbounded above, so later months cannot
    // corrupt the reconstruction). The day walk itself consumes a separately
    // bounded in-window list: bounding this query alone would leave the
    // opening wrong.
    BigDecimal sinceWindow = journalLines
        .sumAmountByAccountIdAndPostedAtGreaterThanEqual(account.getId(), windowStart);
    BigDecimal opening = account.getBalance().subtract(sinceWindow);
    List<JournalLine> postings = journalLines
        .findByAccountIdAndPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAtAsc(
            account.getId(), windowStart, windowEnd);

    BigDecimal dailyRate = savingsAnnualRate
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    StatusCursor status = new StatusCursor(statusHistory
        .findByAccountIdAndChangedAtLessThanOrderByChangedAtAsc(account.getId(), windowEnd),
        windowStart);
    BigDecimal accrued = BigDecimal.ZERO;
    BigDecimal eligibleTotal = BigDecimal.ZERO;
    int eligibleDays = 0;
    int lineIndex = 0;
    BigDecimal running = opening;
    // One interval per business calendar date. Stepping an Instant by 86,400
    // seconds overshoots in zones with daylight saving time (November 2026 in
    // America/Halifax holds 30 dates but 31 fixed-24h steps), which both
    // misprices the month and consumes the next day's midnight postings.
    for (LocalDate day = LocalDate.ofInstant(windowStart, businessZone);
        day.isBefore(LocalDate.ofInstant(windowEnd, businessZone));
        day = day.plusDays(1)) {
      LocalDate nextDate = day.plusDays(1);
      Instant nextDay = nextDate.atStartOfDay(businessZone).toInstant();
      status.advanceTo(nextDay);
      while (lineIndex < postings.size()
          && postings.get(lineIndex).getPostedAt().isBefore(nextDay)) {
        running = running.add(postings.get(lineIndex).getAmount());
        lineIndex++;
      }
      if (status.isActive() && running.signum() > 0) {
        accrued = accrued.add(running.multiply(dailyRate));
        eligibleTotal = eligibleTotal.add(running);
        eligibleDays++;
      }
    }
    BigDecimal posted = accrued.setScale(4, RoundingMode.HALF_EVEN);
    BigDecimal basis = eligibleDays == 0 ? BigDecimal.ZERO
        : eligibleTotal.setScale(4, RoundingMode.HALF_EVEN);
    if (posted.signum() == 0) {
      // A completed-zero month: recorded so resumption never re-argues it.
      recordZero(account.getId(), periodKey, basis, eligibleDays);
      return -1;
    }
    return post(account, periodKey, "Savings interest " + periodKey, true, posted, basis,
        eligibleDays);
  }

  /**
   * Daily closing-principal loan interest: for each day of the period, the
   * closing principal (all principal movements with posted_at before that
   * day's close) times the daily rate. Later draws count only from their own
   * day; a repayment after period end changes nothing inside the period.
   */
  private Integer accrueLoan(Account account, String periodKey, Instant windowStart,
      Instant windowEnd) {
    List<PrincipalMovement> inWindow = principalMovements
        .findByAccountIdAndPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAtAsc(
            account.getId(), windowStart, windowEnd);
    BigDecimal running = principalMovements.sumPostedBefore(account.getId(), windowStart)
        .orElse(BigDecimal.ZERO);

    BigDecimal dailyRate = loanAnnualRate
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    StatusCursor status = new StatusCursor(statusHistory
        .findByAccountIdAndChangedAtLessThanOrderByChangedAtAsc(account.getId(), windowEnd),
        windowStart);
    BigDecimal accrued = BigDecimal.ZERO;
    BigDecimal basisTotal = BigDecimal.ZERO;
    int eligibleDays = 0;
    int index = 0;
    // Same business-calendar walk as savings: one interval per date.
    for (LocalDate day = LocalDate.ofInstant(windowStart, businessZone);
        day.isBefore(LocalDate.ofInstant(windowEnd, businessZone));
        day = day.plusDays(1)) {
      LocalDate nextDate = day.plusDays(1);
      Instant nextDay = nextDate.atStartOfDay(businessZone).toInstant();
      status.advanceTo(nextDay);
      while (index < inWindow.size()
          && inWindow.get(index).getPostedAt().isBefore(nextDay)) {
        running = running.add(inWindow.get(index).getAmount());
        index++;
      }
      if (status.isActive() && running.signum() > 0) {
        accrued = accrued.add(running.multiply(dailyRate));
        basisTotal = basisTotal.add(running);
        eligibleDays++;
      }
    }
    BigDecimal posted = accrued.setScale(4, RoundingMode.HALF_EVEN);
    BigDecimal basis = basisTotal.setScale(4, RoundingMode.HALF_EVEN);
    if (posted.signum() == 0) {
      recordZero(account.getId(), periodKey, basis, eligibleDays);
      return -1;
    }
    return post(account, periodKey, "Loan interest " + periodKey, false, posted, basis,
        eligibleDays);
  }

  /**
   * Walks a day-by-day pricing window applying the account's status history:
   * only days whose close saw the account ACTIVE are eligible, so a frozen
   * period is never charged: including when the account is re-activated and
   * the job later catches up the months the freeze skipped. Accounts start
   * ACTIVE (creation); every transition with changed_at inside the window
   * flips the flag before that day's close is priced.
   */
  private static final class StatusCursor {
    private final List<AccountStatusChange> changes;
    private int index;
    private boolean active = true;

    StatusCursor(List<AccountStatusChange> changes, Instant windowStart) {
      this.changes = changes;
      while (index < changes.size() && changes.get(index).getChangedAt().isBefore(windowStart)) {
        active = changes.get(index).getStatus() == AccountStatus.ACTIVE;
        index++;
      }
    }

    void advanceTo(Instant nextDay) {
      while (index < changes.size() && changes.get(index).getChangedAt().isBefore(nextDay)) {
        active = changes.get(index).getStatus() == AccountStatus.ACTIVE;
        index++;
      }
    }

    boolean isActive() {
      return active;
    }
  }

  /** A completed accrual period whose amount is legitimately zero (V26-era rows). */
  private void recordZero(UUID accountId, String periodKey, BigDecimal basis, int dayCount) {
    accruals.saveAndFlush(new InterestAccrual(accountId, periodKey, RATE_VERSION,
        BigDecimal.ZERO, basis, dayCount, clock.instant()));
  }

  /**
   * Commits a nonzero accrual atomically: accrual row, balance projection,
   * journal posting (balanced against the INTEREST counteraccount), the
   * customer's INTEREST transaction row, audit and notification. {@code
   * credit} is true for savings (the balance rises) and false for loans (the
   * debt deepens; principal is untouched: interest never compounds).
   */
  private Integer post(Account account, String periodKey, String memo, boolean credit,
      BigDecimal amount, BigDecimal basis, int dayCount) {
    Instant now = clock.instant();
    accruals.saveAndFlush(new InterestAccrual(account.getId(), periodKey, RATE_VERSION,
        amount, basis, dayCount, now));

    BigDecimal delta = credit ? amount : amount.negate();
    account.setBalance(account.getBalance().add(delta).setScale(4, RoundingMode.HALF_EVEN));
    account.setLastInterestAt(now);
    accounts.save(account);

    if (credit) {
      journal.post(JournalKind.INTEREST, account.getId() + ":" + periodKey, now, memo,
          JournalService.Posting.account(account.getId(), amount),
          JournalService.Posting.counter(JournalLine.INTEREST, amount.negate()));
    } else {
      journal.post(JournalKind.INTEREST, account.getId() + ":" + periodKey, now, memo,
          JournalService.Posting.account(account.getId(), amount.negate()),
          JournalService.Posting.counter(JournalLine.INTEREST, amount));
    }

    Transaction interest = new Transaction();
    if (credit) {
      interest.setToAccountId(account.getId());
    } else {
      interest.setFromAccountId(account.getId());
    }
    interest.setAmount(amount);
    interest.setCurrency("USD");
    interest.setKind(TxKind.INTEREST);
    interest.setMemo(memo);
    interest.setCreatedAt(now);
    interest.setPostedAt(now);
    transactions.save(interest);
    audits.save(AuditLog.of(account.getUserId(), "INTEREST_POSTED", "Transaction",
        interest.getId().toString(), "amount", amount.toPlainString(), "account",
        account.getIban(), "period", periodKey));
    users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(
        owner.getId(), owner.getEmail(), "INTEREST_POSTED", "Monthly interest posted",
        (credit ? "Earned " : "Charged ")
            + Money.usd(amount) + " on account " + account.getIban() + "."));
    return 1;
  }

  /**
   * The expected duplicate is the (account, period) uniqueness on
   * {@code interest_accruals}: everything else is a real integrity failure
   * and must surface. Constraint names/messages differ between PostgreSQL and
   * H2, so both the named constraint and the H2 shape are recognised.
   */
  private static boolean isExpectedDuplicate(DataIntegrityViolationException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      String message = String.valueOf(cause.getMessage());
      String lower = message.toLowerCase();
      if (message.contains("uq_accrual_account_period")) {
        return true;
      }
      if (lower.contains("unique") && lower.contains("interest_accruals")) {
        return true;
      }
    }
    return false;
  }
}
