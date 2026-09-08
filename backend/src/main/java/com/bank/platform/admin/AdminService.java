package com.bank.platform.admin;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountStatusChange;
import com.bank.platform.accounts.AccountStatusChangeRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.ledger.DecisionConflictException;
import com.bank.platform.ledger.HeldTransferService;
import com.bank.platform.ledger.Transaction;
import com.bank.platform.ledger.TransactionNotFoundException;
import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.ledger.TxStatus;
import com.bank.platform.auth.Role;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.notifications.NotificationService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
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
  private final HeldTransferService heldTransfers;
  private final AccountStatusChangeRepository statusHistory;
  private final Clock clock;

  public AdminService(UserRepository users, AccountRepository accounts, AuditLogRepository audits,
      NotificationService notifications, TransactionRepository transactions, HeldTransferService heldTransfers,
      AccountStatusChangeRepository statusHistory, Clock clock) {
    this.users = users;
    this.accounts = accounts;
    this.audits = audits;
    this.notifications = notifications;
    this.transactions = transactions;
    this.heldTransfers = heldTransfers;
    this.statusHistory = statusHistory;
    this.clock = clock;
  }

  /**
   * Operator decision on a queue item. Every decision
   * carries a bounded, required-in-the-UI reason and the case state the
   * operator SAW ({@code expectedStatus}/{@code expectedReviewed}); when the
   * row is no longer in that state a {@link DecisionConflictException} (409)
   * tells the losing console to refresh and show the winning decision.
   *
   * A HELD transfer (large transfer awaiting review) is APPROVED: money moves
   * under locks inside the same transaction, the row becomes POSTED, and both
   * parties are notified - all inside {@link HeldTransferService#settleHeldTransfer},
   * which owns the whole held-transfer lifecycle. A flagged-but-posted row (e.g. a
   * large simulated deposit, which credits the moment it arrives) is
   * acknowledged here: there is no money to settle, so the flag is cleared.
   */
  @Transactional
  public Transaction reviewTransaction(String adminEmail, UUID transactionId,
      String decisionReason, String expectedStatus, Boolean expectedReviewed) {
    User admin = operatorOf(adminEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    requireExpected(tx, expectedStatus, expectedReviewed);

    if (tx.getStatus() == TxStatus.HELD) {
      requireNotSelfReview(admin, tx);
      return heldTransfers.settleHeldTransfer(adminEmail, transactionId, decisionReason);
    }

    // Only posted flagged rows (e.g. a large simulated deposit that credits on
    // arrival) are acknowledged; a CANCELLED or already-resolved row is not a
    // live queue item anymore - and an already-reviewed one is a race loss.
    if (tx.getStatus() != TxStatus.POSTED || !tx.isFlagged() || tx.isReviewed()) {
      throw new DecisionConflictException(transactionId, tx.getStatus().name(), tx.isReviewed());
    }
    tx.setReviewed(true);
    transactions.save(tx);
    audits.save(AuditLog.of(admin.getId(), "TRANSACTION_REVIEWED", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(),
        "account", tx.getToAccountId() == null ? "" : tx.getToAccountId().toString(),
        "reason", defaultReason(decisionReason)));
    return tx;
  }

  /** Declines a HELD transfer - the lifecycle logic lives in the ledger. */
  @Transactional
  public Transaction declineTransaction(String adminEmail, UUID transactionId,
      String decisionReason, String expectedStatus, Boolean expectedReviewed) {
    User admin = operatorOf(adminEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    requireExpected(tx, expectedStatus, expectedReviewed);
    if (tx.getStatus() != TxStatus.HELD) {
      throw new DecisionConflictException(transactionId, tx.getStatus().name(), tx.isReviewed());
    }
    requireNotSelfReview(admin, tx);
    return heldTransfers.declineHeldTransfer(adminEmail, transactionId, decisionReason);
  }

  /** A stale expected state means the case already moved - surface it as a conflict. */
  private static void requireExpected(Transaction tx, String expectedStatus, Boolean expectedReviewed) {
    if (expectedStatus != null && !expectedStatus.equals(tx.getStatus().name())) {
      throw new DecisionConflictException(tx.getId(), tx.getStatus().name(), tx.isReviewed());
    }
    if (expectedReviewed != null && expectedReviewed != tx.isReviewed()) {
      throw new DecisionConflictException(tx.getId(), tx.getStatus().name(), tx.isReviewed());
    }
  }

  /** An operator cannot approve/decline a transfer they originated themselves. */
  private void requireNotSelfReview(User admin, Transaction tx) {
    if (tx.getFromAccountId() != null) {
      accounts.findById(tx.getFromAccountId()).ifPresent(from -> {
        if (from.getUserId().equals(admin.getId())) {
          throw new AccessDeniedException("An operator cannot review their own transfer");
        }
      });
    }
  }

  /** API/legacy callers without a typed reason get one bounded default. */
  static String defaultReason(String reason) {
    if (reason == null) {
      return "Operator decision";
    }
    String trimmed = reason.trim();
    return trimmed.isEmpty() ? "Operator decision" : trimmed;
  }

  @Transactional
  public Account setStatus(String adminEmail, UUID accountId, AccountStatus status) {
    User admin = operatorOf(adminEmail);
    if (admin.getRole() != Role.ADMIN) {
      throw new AccessDeniedException("Admins only");
    }
    Account account = accounts.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    account.setStatus(status);
    accounts.save(account);
    // Immutable transition record (V27) in the SAME transaction: the interest
    // job prices only days the account was ACTIVE, so a frozen period is never
    // charged - now or retroactively after re-activation.
    statusHistory.save(new AccountStatusChange(account.getId(), status, clock.instant()));
    String action = status == AccountStatus.FROZEN ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN";
    audits.save(AuditLog.of(admin.getId(), action, "Account", account.getId().toString(),
        "iban", account.getIban(), "status", status.name()));
    users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        action, status == AccountStatus.FROZEN ? "Account frozen" : "Account re-activated",
        "Account " + account.getIban() + (status == AccountStatus.FROZEN ? " was frozen by operations." : " is active again.")));
    return account;
  }

  private User operatorOf(String adminEmail) {
    return users.findByEmail(adminEmail)
        .orElseThrow(() -> new UsernameNotFoundException("Admin not found"));
  }
}
