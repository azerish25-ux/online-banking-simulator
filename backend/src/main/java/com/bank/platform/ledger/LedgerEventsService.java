package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.Money;
import com.bank.platform.notifications.NotificationService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Single owner of the record-and-alert side effects of a ledger money event:
 * each completed deposit / transfer / held-intent / approval / decline emits
 * exactly one audit row (the durable record) and the notifications to the
 * parties involved (the alert). Copy and audit actions are user-facing, so
 * they are centralized here instead of being rebuilt at every call site.
 */
@Service
public class LedgerEventsService {

  private final AuditLogRepository audits;
  private final NotificationService notifications;
  private final UserRepository users;
  private final AccountRepository accounts;

  public LedgerEventsService(AuditLogRepository audits, NotificationService notifications,
      UserRepository users, AccountRepository accounts) {
    this.audits = audits;
    this.notifications = notifications;
    this.users = users;
    this.accounts = accounts;
  }

  public void depositPosted(User depositor, Transaction tx, Account account) {
    audits.save(AuditLog.of(depositor.getId(), "DEPOSIT_POSTED", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(), "to", account.getIban()));
    notifications.notify(depositor.getId(), depositor.getEmail(), "DEPOSIT_POSTED", "Deposit received",
        "Deposited " + Money.usd(tx.getAmount()) + " to " + account.getIban() + ".");
  }

  /** An instant (below-threshold) transfer settled immediately. */
  public void transferPosted(User sender, Transaction tx, Account from, Account to) {
    audits.save(AuditLog.of(sender.getId(), "TRANSFER_POSTED", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban()));
    notifications.notify(sender.getId(), sender.getEmail(), "TRANSFER_SENT", "Money sent",
        "Sent " + Money.usd(tx.getAmount()) + " to " + to.getIban() + ".");
    users.findById(to.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_RECEIVED", "Money received",
        "Received " + Money.usd(tx.getAmount()) + " from " + from.getIban() + "."));
  }

  /** A review-threshold transfer was recorded as an intent; nothing has moved. */
  public void transferHeld(User sender, Transaction tx, Account from, Account to) {
    audits.save(AuditLog.of(sender.getId(), "TRANSFER_HELD", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban()));
    notifications.notify(sender.getId(), sender.getEmail(), "TRANSFER_HELD", "Transfer held for review",
        "Your transfer of " + Money.usd(tx.getAmount()) + " to " + to.getIban()
            + " is held for operator review; no money has moved yet.");
  }

  /** A HELD transfer was approved and settled; the sender and recipient are notified. */
  public void transferApproved(User actor, Transaction tx, Account from, Account to, String decisionReason) {
    audits.save(AuditLog.of(actor.getId(), "TRANSFER_APPROVED", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban(),
        "reason", boundedReason(decisionReason)));
    users.findById(from.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_SENT", "Money sent",
        "Sent " + Money.usd(tx.getAmount()) + " to " + to.getIban() + "."));
    users.findById(to.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_RECEIVED", "Money received",
        "Received " + Money.usd(tx.getAmount()) + " from " + from.getIban() + "."));
  }

  /** A HELD transfer was declined: no money moved, and only the sender is told. */
  public void transferDeclined(User actor, Transaction tx, String decisionReason) {
    audits.save(AuditLog.of(actor.getId(), "TRANSFER_DECLINED", "Transaction", tx.getId().toString(),
        "amount", tx.getAmount().toPlainString(),
        "from", ibanOf(tx.getFromAccountId()), "to", ibanOf(tx.getToAccountId()),
        "reason", boundedReason(decisionReason)));
    accounts.findById(tx.getFromAccountId()).ifPresent(from -> users.findById(from.getUserId())
        .ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
            "TRANSFER_DECLINED", "Transfer declined",
            "Your transfer of " + Money.usd(tx.getAmount()) + " to " + ibanOf(tx.getToAccountId())
                + " was declined by operations; no money moved.")));
  }

  /** An operator reversed a POSTED transfer: the money moves back along its legs. */
  public void transactionReversed(User actor, Transaction original, Transaction reversal,
      Account from, Account to) {
    audits.save(AuditLog.of(actor.getId(), "TRANSACTION_REVERSED", "Transaction",
        reversal.getId().toString(),
        "reverses", original.getId().toString(),
        "amount", reversal.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban(),
        "reason", reversal.getReversalReason()));
    users.findById(from.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSACTION_REVERSED", "Transfer reversed",
        "A transfer of " + Money.usd(reversal.getAmount()) + " was reversed on your account "
            + from.getIban() + "."));
    users.findById(to.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSACTION_REVERSED", "Transfer reversed",
        "A transfer of " + Money.usd(reversal.getAmount()) + " was reversed on your account "
            + to.getIban() + "."));
  }

  /** An operator reversed a POSTED deposit: the credited account is debited back to the rail. */
  public void depositReversed(User actor, Transaction original, Transaction reversal, Account account) {
    audits.save(AuditLog.of(actor.getId(), "DEPOSIT_REVERSED", "Transaction",
        reversal.getId().toString(),
        "reverses", original.getId().toString(),
        "amount", reversal.getAmount().toPlainString(),
        "account", account.getIban(),
        "reason", reversal.getReversalReason()));
    users.findById(account.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "DEPOSIT_REVERSED", "Deposit reversed",
        "A deposit of " + Money.usd(reversal.getAmount()) + " on " + account.getIban()
            + " was reversed by operations."));
  }

  private String ibanOf(UUID accountId) {
    return accounts.findById(accountId).map(Account::getIban).orElse("");
  }

  /** Decision reasons are bounded, rendered-safe metadata: never whole
   *  request payloads. */
  private static String boundedReason(String reason) {
    if (reason == null) {
      return "Operator decision";
    }
    String trimmed = reason.trim();
    return trimmed.length() > 400 ? trimmed.substring(0, 400) : trimmed;
  }
}
