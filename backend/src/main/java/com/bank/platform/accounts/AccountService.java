package com.bank.platform.accounts;

import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import com.bank.platform.notifications.NotificationService;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account identity and lifecycle: which accounts a caller owns, reading one
 * account (foreign accounts are indistinguishable from missing ones - 404,
 * no existence oracle), and opening new accounts. Money movement lives in the
 * ledger; this service only decides who may touch what and creates accounts.
 */
@Service
public class AccountService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final AuditLogRepository audits;
  private final NotificationService notifications;
  private final LedgerCacheInvalidation invalidation;

  public AccountService(
      UserRepository users,
      AccountRepository accounts,
      AuditLogRepository audits,
      NotificationService notifications,
      LedgerCacheInvalidation invalidation) {
    this.users = users;
    this.accounts = accounts;
    this.invalidation = invalidation;
    this.audits = audits;
    this.notifications = notifications;
  }

  @Transactional(readOnly = true)
  public List<Account> myAccounts(String email) {
    return accounts.findByUserIdOrderByCreatedAtAsc(userOf(email).getId());
  }

  /**
   * Reads a caller-owned account. A foreign account is indistinguishable from
   * a missing one (404) so account existence is never disclosed to outsiders.
   */
  @Transactional(readOnly = true)
  public Account accountDetail(String email, UUID accountId) {
    Account account = accounts.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    if (!account.getUserId().equals(userOf(email).getId())) {
      throw new AccountNotFoundException(accountId);
    }
    return account;
  }

  @Transactional
  public Account openAccount(String email, String type) {
    String clean = type == null ? "" : type.trim().toUpperCase();
    final AccountType accountType;
    try {
      accountType = AccountType.valueOf(clean);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("Unknown account type: " + type);
    }
    User user = userOf(email);
    if (accountType == AccountType.LOAN && accounts.countByUserIdAndType(user.getId(), AccountType.LOAN) > 0) {
      // Without the cap a user could mint unbounded $1,000 credit by opening
      // loan after loan and transferring the proceeds out (PostgreSQL enforces
      // the same rule with a partial unique index - see V15; H2 covers it here).
      throw new IllegalArgumentException("You already have a loan - settle it before opening another.");
    }
    Account account = new Account(user.getId(), Iban.uniqueOrThrow(accounts::existsByIban, 5), accountType);
    if (accountType == AccountType.LOAN) {
      account.setCreditLimit(new java.math.BigDecimal("1000.00"));
    }
    accounts.save(account);
    audits.save(AuditLog.of(user.getId(), "ACCOUNT_OPENED", "Account", account.getId().toString(),
        "iban", account.getIban(), "type", account.getType().name()));
    notifications.notify(user.getId(), user.getEmail(), "ACCOUNT_OPENED", "Account opened",
        clean.charAt(0) + clean.substring(1).toLowerCase() + " account " + account.getIban() + " is ready.");
    invalidation.clearSynchronized("public-stats");
    return account;
  }

  private User userOf(String email) {
    return users.findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }
}
