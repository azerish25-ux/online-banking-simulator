package com.bank.platform.admin;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.ledger.Transaction;
import com.bank.platform.ledger.TransactionNotFoundException;
import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.notifications.NotificationService;
import java.util.UUID;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;
  private final NotificationService notifications;
  private final TransactionRepository transactions;

  public AdminService(UserRepository users, AccountRepository accounts, AuditLogRepository audits,
      NotificationService notifications, TransactionRepository transactions) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
    this.notifications = notifications;
    this.transactions = transactions;
  }

  @Transactional
  public Transaction reviewTransaction(String adminEmail, UUID transactionId) {
    User admin = users.findByEmail(adminEmail)
        .orElseThrow(() -> new UsernameNotFoundException("Admin not found"));
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    tx.setReviewed(true);
    transactions.save(tx);
    audits.save(new AuditLog(admin.getId(), "TRANSACTION_REVIEWED", "Transaction", tx.getId().toString()));
    return tx;
  }

  @Transactional
  public Account setStatus(String adminEmail, UUID accountId, String status) {
    User admin = users.findByEmail(adminEmail)
        .orElseThrow(() -> new UsernameNotFoundException("Admin not found"));
    if (!"ADMIN".equals(admin.getRole())) {
      throw new org.springframework.security.access.AccessDeniedException("Admins only");
    }
    Account account = accounts.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    account.setStatus(status);
    accounts.save(account);
    String action = "FROZEN".equals(status) ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN";
    audits.save(new AuditLog(admin.getId(), action, "Account", account.getId().toString()));
    users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        action, "FROZEN".equals(status) ? "Account frozen" : "Account re-activated",
        "Account " + account.getIban() + ("FROZEN".equals(status) ? " was frozen by operations." : " is active again.")));
    return account;
  }
}
