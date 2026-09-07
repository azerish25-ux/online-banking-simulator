package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The HELD-transfer lifecycle. A review-threshold transfer is recorded as an
 * intent (no money moves); an operator then either settles it - money moves
 * under the same locks as an instant transfer - or cancels it. Everything
 * about a held row's journey lives here so the transfer entry point only has
 * to decide whether a transfer is held or not.
 */
@Service
public class HeldTransferService {

  private final UserRepository users;
  private final TransactionRepository transactions;
  private final LedgerMovementService movement;
  private final LedgerEventsService events;
  private final JournalService journal;
  private final PrincipalMovementRepository principalMovements;
  private final Clock clock;
  private final LedgerCacheInvalidation invalidation;

  public HeldTransferService(UserRepository users, TransactionRepository transactions,
      LedgerMovementService movement, LedgerEventsService events, JournalService journal,
      PrincipalMovementRepository principalMovements, Clock clock,
      LedgerCacheInvalidation invalidation) {
    this.users = users;
    this.transactions = transactions;
    this.movement = movement;
    this.events = events;
    this.journal = journal;
    this.principalMovements = principalMovements;
    this.clock = clock;
    this.invalidation = invalidation;
  }

  /**
   * Review-threshold path: records the intent as HELD without moving money.
   * Funds are re-checked under locks when an operator approves; the sender is
   * told the transfer is awaiting review rather than sent. The canonical
   * intent hash (F06) rides the row so an identical replay returns the HELD
   * intent and a changed payload under the same key is a conflict.
   */
  public Transaction holdForReview(User sender, Account fromRef, Account toRef, BigDecimal scaled,
      String currency, String memo, String key, String requestHash) {
    movement.requireActive(fromRef);
    movement.requireActive(toRef);
    // Fail-fast affordability check (unlocked read); the authoritative check
    // happens again under locks when the operator approves.
    movement.assertAffordable(fromRef, scaled);

    Transaction tx = new Transaction();
    tx.setFromAccountId(fromRef.getId());
    tx.setToAccountId(toRef.getId());
    tx.setAmount(scaled);
    tx.setCurrency(currency);
    tx.setKind(TxKind.TRANSFER);
    tx.setMemo(memo);
    tx.setIdempotencyKey(key);
    tx.setRequestHash(requestHash);
    tx.setFlagged(true);
    tx.setStatus(TxStatus.HELD);
    // Request time only - a HELD row has no posting time until an operator
    // settles it (possibly after a month boundary); createdAt is submission.
    tx.setCreatedAt(clock.instant());
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException race) {
      throw new IdempotencyConflictException(
          "This transfer is already being processed; retry the identical request to fetch it");
    }

    events.transferHeld(sender, tx, fromRef, toRef);
    return tx;
  }

  /**
   * Approves a held transfer: moves money from sender to recipient under the
   * same ID-ordered locks as an instant transfer and marks the row POSTED.
   * The HELD → POSTED flip is atomic (see TransactionRepository), so two
   * operators approving at once cannot double-settle. The transfer-lifecycle
   * audit and notifications live here with the settlement they describe - one
   * owner for the whole held-transfer flow.
   */
  @Transactional
  public Transaction settleHeldTransfer(String actorEmail, UUID transactionId, String decisionReason) {
    User actor = userOf(actorEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (tx.getStatus() != TxStatus.HELD || tx.getFromAccountId() == null || tx.getToAccountId() == null) {
      // Not a settleable case: another operator already decided it (POSTED /
      // CANCELLED) or the row is malformed. A decided row is a 409 - the
      // losing operator must see the winning outcome, not an error toast.
      throw new DecisionConflictException(transactionId, tx.getStatus().name(), tx.isReviewed());
    }
    // Settlement time (F04): reported/statement months cut here, and this is
    // the moment the money actually moved - approval can land well after the
    // request, across a month or year boundary. The stamp rides the atomic
    // HELD→POSTED flip so the transition and its time cannot diverge.
    Instant postedAt = clock.instant();
    if (transactions.resolveAwaitingReview(transactionId, TxStatus.HELD, TxStatus.POSTED, postedAt) == 0) {
      // The atomic flip lost: someone else settled (or cancelled) this HELD
      // row between our read and the flip. Surface the CURRENT state so the
      // losing console can refresh and show the winner's decision (F16).
      Transaction current = transactions.findById(transactionId)
          .orElseThrow(() -> new TransactionNotFoundException(transactionId));
      throw new DecisionConflictException(transactionId, current.getStatus().name(), current.isReviewed());
    }

    LedgerMovementService.Moved moved = movement.move(tx.getFromAccountId(), tx.getToAccountId(), tx.getAmount());
    tx.setStatus(TxStatus.POSTED);
    tx.setReviewed(true);
    tx.setPostedAt(postedAt);
    transactions.save(tx);

    // Approval is when money actually moves, so THIS is where the journal is
    // written - a HELD intent created no entry, and the losing operator in an
    // approval race rolls back before ever reaching here (F15). The posting
    // time stamped on the row and the journal's are one instant. A draw or a
    // principal repayment in a settled transfer also leaves its immutable
    // principal-movement row (V26), keyed to this transaction.
    for (LedgerMovementService.PrincipalEvent event : moved.principalEvents()) {
      principalMovements.save(new PrincipalMovement(event.accountId(), event.kind(),
          event.signedAmount(), tx.getId(), postedAt));
    }
    journal.post(JournalKind.TRANSFER, tx.getId().toString(), postedAt,
        "Held transfer " + tx.getAmount().toPlainString() + " approved",
        JournalService.Posting.account(tx.getFromAccountId(), tx.getAmount().negate()),
        JournalService.Posting.account(tx.getToAccountId(), tx.getAmount()));

    events.transferApproved(actor, tx, moved.from(), moved.to(), decisionReason);
    // Evict only after this settlement commits (F07) - a rollback must not
    // clear caches for an approval that never happened.
    invalidation.clearSynchronized("summaries", "public-stats");
    return tx;
  }

  /**
   * Declines a HELD transfer. Nothing has moved (held transfers never settle
   * without approval), so the row becomes CANCELLED and the sender is told no
   * money left their account.
   */
  @Transactional
  public Transaction declineHeldTransfer(String actorEmail, UUID transactionId, String decisionReason) {
    User actor = userOf(actorEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (tx.getStatus() != TxStatus.HELD) {
      throw new DecisionConflictException(transactionId, tx.getStatus().name(), tx.isReviewed());
    }
    if (transactions.resolveAwaitingReview(transactionId, TxStatus.HELD, TxStatus.CANCELLED, null) == 0) {
      Transaction current = transactions.findById(transactionId)
          .orElseThrow(() -> new TransactionNotFoundException(transactionId));
      throw new DecisionConflictException(transactionId, current.getStatus().name(), current.isReviewed());
    }
    tx.setStatus(TxStatus.CANCELLED);
    tx.setReviewed(true);
    transactions.save(tx);
    // No money moved, but the request-history view changed - clear after
    // commit so the same transaction never observes a half-declined row.
    invalidation.clearSynchronized("summaries", "public-stats");

    events.transferDeclined(actor, tx, decisionReason);
    return tx;
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

}
