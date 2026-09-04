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
    AuditLog deposited = new AuditLog(depositor.getId(), "DEPOSIT_POSTED", "Transaction", tx.getId().toString());
    deposited.setMetadata(AuditLog.metadata("amount", tx.getAmount().toPlainString(), "to", account.getIban()));
    audits.save(deposited);
    notifications.notify(depositor.getId(), depositor.getEmail(), "DEPOSIT_POSTED", "Deposit received",
        "Deposited " + Money.usd(tx.getAmount()) + " to " + account.getIban() + ".");
  }

  /** An instant (below-threshold) transfer settled immediately. */
  public void transferPosted(User sender, Transaction tx, Account from, Account to) {
    AuditLog posted = new AuditLog(sender.getId(), "TRANSFER_POSTED", "Transaction", tx.getId().toString());
    posted.setMetadata(AuditLog.metadata("amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban()));
    audits.save(posted);
    notifications.notify(sender.getId(), sender.getEmail(), "TRANSFER_SENT", "Money sent",
        "Sent " + Money.usd(tx.getAmount()) + " to " + to.getIban() + ".");
    users.findById(to.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_RECEIVED", "Money received",
        "Received " + Money.usd(tx.getAmount()) + " from " + from.getIban() + "."));
  }

  /** A review-threshold transfer was recorded as an intent; nothing has moved. */
  public void transferHeld(User sender, Transaction tx, Account from, Account to) {
    AuditLog held = new AuditLog(sender.getId(), "TRANSFER_HELD", "Transaction", tx.getId().toString());
    held.setMetadata(AuditLog.metadata("amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban()));
    audits.save(held);
    notifications.notify(sender.getId(), sender.getEmail(), "TRANSFER_HELD", "Transfer held for review",
        "Your transfer of " + Money.usd(tx.getAmount()) + " to " + to.getIban()
            + " is held for operator review; no money has moved yet.");
  }

  /** A HELD transfer was approved and settled; the sender and recipient are notified. */
  public void transferApproved(User actor, Transaction tx, Account from, Account to) {
    AuditLog approved = new AuditLog(actor.getId(), "TRANSFER_APPROVED", "Transaction", tx.getId().toString());
    approved.setMetadata(AuditLog.metadata("amount", tx.getAmount().toPlainString(),
        "from", from.getIban(), "to", to.getIban()));
    audits.save(approved);
    users.findById(from.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_SENT", "Money sent",
        "Sent " + Money.usd(tx.getAmount()) + " to " + to.getIban() + "."));
    users.findById(to.getUserId()).ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
        "TRANSFER_RECEIVED", "Money received",
        "Received " + Money.usd(tx.getAmount()) + " from " + from.getIban() + "."));
  }

  /** A HELD transfer was declined: no money moved, and only the sender is told. */
  public void transferDeclined(User actor, Transaction tx) {
    AuditLog declined = new AuditLog(actor.getId(), "TRANSFER_DECLINED", "Transaction", tx.getId().toString());
    declined.setMetadata(AuditLog.metadata("amount", tx.getAmount().toPlainString(),
        "from", ibanOf(tx.getFromAccountId()), "to", ibanOf(tx.getToAccountId())));
    audits.save(declined);
    accounts.findById(tx.getFromAccountId()).ifPresent(from -> users.findById(from.getUserId())
        .ifPresent(owner -> notifications.notify(owner.getId(), owner.getEmail(),
            "TRANSFER_DECLINED", "Transfer declined",
            "Your transfer of " + Money.usd(tx.getAmount()) + " to " + ibanOf(tx.getToAccountId())
                + " was declined by operations; no money moved.")));
  }

  private String ibanOf(UUID accountId) {
    return accounts.findById(accountId).map(Account::getIban).orElse("");
  }
}
