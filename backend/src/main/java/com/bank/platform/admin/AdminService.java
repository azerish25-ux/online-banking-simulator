package com.bank.platform.admin;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import java.util.UUID;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;

  public AdminService(UserRepository users, AccountRepository accounts, AuditLogRepository audits) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
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
    audits.save(new AuditLog(
        admin.getId(), "FROZEN".equals(status) ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN",
        "Account", account.getId().toString()));
    return account;
  }
}
