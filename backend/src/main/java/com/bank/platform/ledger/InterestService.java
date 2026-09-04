package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.Money;
import com.bank.platform.notifications.NotificationService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InterestService {

  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final AuditLogRepository audits;
  private final UserRepository users;
  private final NotificationService notifications;
  private final BigDecimal savingsAnnualRate;
  private final BigDecimal loanAnnualRate;

  public InterestService(
      AccountRepository accounts,
      TransactionRepository transactions,
      AuditLogRepository audits,
      UserRepository users,
      NotificationService notifications,
      @Value("${app.interest.savings-annual-rate:0.04}") BigDecimal savingsAnnualRate,
      @Value("${app.interest.loan-annual-rate:0.12}") BigDecimal loanAnnualRate) {
    this.accounts = accounts;
    this.transactions = transactions;
    this.audits = audits;
    this.users = users;
    this.notifications = notifications;
    this.savingsAnnualRate = savingsAnnualRate;
    this.loanAnnualRate = loanAnnualRate;
  }

  /** Runs at 03:00 on the first of every month. Also triggerable via the admin API for demos. */
  @CacheEvict(value = "summaries", allEntries = true)
  @Scheduled(cron = "0 0 3 1 * *")
  @Transactional
  public Map<String, Integer> accrueMonthly() {
    YearMonth month = YearMonth.now(ZoneOffset.UTC);
    // Pushed down to the database: only savings/loan, active, not-yet-accrued-this-month rows leave it.
    List<Account> candidates = accounts.findInterestCandidates(
        List.of(AccountType.SAVINGS, AccountType.LOAN), AccountStatus.ACTIVE,
        month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());

    int posted = 0;
    for (Account account : candidates) {
      // Belt and braces on top of the FOR UPDATE lock: if a competing run
      // committed this account's accrual while we waited for the lock, the
      // re-read row now carries this month's lastInterestAt - skip it so
      // interest can never post twice for the same account-month.
      if (account.getLastInterestAt() != null
          && !account.getLastInterestAt().isBefore(month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant())) {
        continue;
      }
      BigDecimal monthlyRate = account.getType() == AccountType.SAVINGS
          ? savingsAnnualRate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN)
          : loanAnnualRate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN);
      BigDecimal delta = account.getBalance().multiply(monthlyRate)
          .setScale(4, RoundingMode.HALF_EVEN);
      if (account.getType() == AccountType.LOAN) {
        // Loans only accrue while money is owed, and interest deepens the negative balance.
        if (account.getBalance().compareTo(BigDecimal.ZERO) >= 0) {
          continue;
        }
        // A loan drawn to its full limit cannot go deeper: the DB guarantees
        // balance >= -credit_limit, so a charge that would breach it is capped
        // at the remaining headroom. Otherwise one maxed loan would abort the
        // whole monthly run for every account.
        BigDecimal chargeCap = account.getCreditLimit().negate().subtract(account.getBalance());
        // e.g. balance -999.50 vs limit -1000 → can charge at most -0.50 more.
        if (delta.compareTo(chargeCap) < 0) {
          delta = chargeCap;
        }
        // A legitimate charge is negative (it deepens the debt); only a cap
        // that leaves zero headroom means there is nothing to post.
        if (delta.compareTo(BigDecimal.ZERO) == 0) {
          continue;
        }
      } else if (delta.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      // Post the (possibly capped) delta; the amount written to the ledger is
      // effectively final so lambdas below can reference it.
      BigDecimal amountPosted = delta.abs();
      account.setBalance(account.getBalance().add(delta).setScale(4, RoundingMode.HALF_EVEN));
      account.setLastInterestAt(java.time.Instant.now());
      accounts.save(account);

      Transaction tx = new Transaction();
      if (account.getType() == AccountType.SAVINGS) {
        tx.setToAccountId(account.getId());
        tx.setMemo("Savings interest " + month);
      } else {
        tx.setFromAccountId(account.getId());
        tx.setMemo("Loan interest " + month);
      }
      tx.setAmount(amountPosted);
      tx.setCurrency("USD");
      tx.setKind(TxKind.INTEREST);
      transactions.save(tx);
      AuditLog interest = new AuditLog(account.getUserId(), "INTEREST_POSTED", "Transaction", tx.getId().toString());
      interest.setMetadata(AuditLog.metadata("amount", amountPosted.toPlainString(), "account", account.getIban(), "month", month.toString()));
      audits.save(interest);
      users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(
          owner.getId(), owner.getEmail(), "INTEREST_POSTED", "Monthly interest posted",
          (account.getType() == AccountType.SAVINGS ? "Earned " : "Charged ")
              + Money.usd(amountPosted) + " on account " + account.getIban() + "."));
      posted++;
    }
    return Map.of("accrued", posted);
  }
}
