package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.UserRepository;
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
    List<Account> candidates = accounts.findAll().stream()
        .filter(a -> "SAVINGS".equals(a.getType()) || "LOAN".equals(a.getType()))
        .filter(a -> "ACTIVE".equals(a.getStatus()))
        .filter(a -> a.getLastInterestAt() == null
            || YearMonth.from(a.getLastInterestAt().atZone(ZoneOffset.UTC)).isBefore(month))
        .toList();

    int posted = 0;
    for (Account account : candidates) {
      BigDecimal monthlyRate = "SAVINGS".equals(account.getType())
          ? savingsAnnualRate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN)
          : loanAnnualRate.divide(BigDecimal.valueOf(12), 10, RoundingMode.HALF_EVEN);
      BigDecimal delta = account.getBalance().multiply(monthlyRate)
          .setScale(4, RoundingMode.HALF_EVEN);
      if ("LOAN".equals(account.getType())) {
        // Loans only accrue while money is owed, and interest deepens the negative balance.
        if (account.getBalance().compareTo(BigDecimal.ZERO) >= 0) {
          continue;
        }
      } else if (delta.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      account.setBalance(account.getBalance().add(delta).setScale(4, RoundingMode.HALF_EVEN));
      account.setLastInterestAt(java.time.Instant.now());
      accounts.save(account);

      Transaction tx = new Transaction();
      if ("SAVINGS".equals(account.getType())) {
        tx.setToAccountId(account.getId());
        tx.setMemo("Savings interest " + month);
      } else {
        tx.setFromAccountId(account.getId());
        tx.setMemo("Loan interest " + month);
      }
      tx.setAmount(delta.abs());
      tx.setCurrency("USD");
      tx.setKind("INTEREST");
      transactions.save(tx);
      audits.save(new AuditLog(account.getUserId(), "INTEREST_POSTED", "Transaction", tx.getId().toString()));
      users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(
          owner.getId(), owner.getEmail(), "INTEREST_POSTED", "Monthly interest posted",
          ("SAVINGS".equals(account.getType()) ? "Earned " : "Charged ")
              + delta.abs().toPlainString() + " USD on account " + account.getIban() + "."));
      posted++;
    }
    return Map.of("accrued", posted);
  }
}
