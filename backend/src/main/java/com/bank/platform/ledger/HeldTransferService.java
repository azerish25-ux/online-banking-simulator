package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
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

  public HeldTransferService(UserRepository users, TransactionRepository transactions,
      LedgerMovementService movement, LedgerEventsService events) {
    this.users = users;
    this.transactions = transactions;
    this.movement = movement;
    this.events = events;
  }

  /**
   * Review-threshold path: records the intent as HELD without moving money.
   * Funds are re-checked under locks when an operator approves; the sender is
   * told the transfer is awaiting review rather than sent.
   */
  public Transaction holdForReview(User sender, Account fromRef, Account toRef, BigDecimal scaled,
      String currency, String memo, String cleanKey, boolean keyed) {
    movement.requireActive(fromRef);
    movement.requireActive(toRef);
    // Fail-fast affordability check (unlocked read); the authoritative check
    // happens again under locks when the operator approves.
    movement.assertAffordable(fromRef, scaled);

    Transaction tx = new Transaction();
    tx.setFromAccountId(fromRef.getId());
    tx.setToAccountId(toRef.getId());
    tx.setAmount(scaled);
    tx.setCurrency(Currencies.normalize(currency));
    tx.setKind(TxKind.TRANSFER);
    tx.setMemo(memo);
    tx.setIdempotencyKey(cleanKey);
    tx.setFlagged(true);
    tx.setStatus(TxStatus.HELD);
    try {
      transactions.saveAndFlush(tx);
    } catch (DataIntegrityViolationException race) {
      if (!keyed) {
        throw race;
      }
      throw new TransferValidationException(
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
  @CacheEvict(value = {"summaries", "public-stats"}, allEntries = true)
  @Transactional
  public Transaction settleHeldTransfer(String actorEmail, UUID transactionId) {
    User actor = userOf(actorEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (tx.getStatus() != TxStatus.HELD || tx.getFromAccountId() == null || tx.getToAccountId() == null) {
      throw new TransferValidationException("Only held transfers can be settled");
    }
    if (transactions.resolveAwaitingReview(transactionId, TxStatus.HELD, TxStatus.POSTED) == 0) {
      throw new TransferValidationException("Transfer was already resolved by someone else");
    }

    LedgerMovementService.Moved moved = movement.move(tx.getFromAccountId(), tx.getToAccountId(), tx.getAmount());
    tx.setStatus(TxStatus.POSTED);
    tx.setReviewed(true);
    transactions.save(tx);

    events.transferApproved(actor, tx, moved.from(), moved.to());
    return tx;
  }

  /**
   * Declines a HELD transfer. Nothing has moved (held transfers never settle
   * without approval), so the row becomes CANCELLED and the sender is told no
   * money left their account.
   */
  @CacheEvict(value = {"summaries", "public-stats"}, allEntries = true)
  @Transactional
  public Transaction declineHeldTransfer(String actorEmail, UUID transactionId) {
    User actor = userOf(actorEmail);
    Transaction tx = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (tx.getStatus() != TxStatus.HELD) {
      throw new TransferValidationException("Only held transfers can be declined");
    }
    if (transactions.resolveAwaitingReview(transactionId, TxStatus.HELD, TxStatus.CANCELLED) == 0) {
      throw new TransferValidationException("Transfer was already resolved by someone else");
    }
    tx.setStatus(TxStatus.CANCELLED);
    tx.setReviewed(true);
    transactions.save(tx);

    events.transferDeclined(actor, tx);
    return tx;
  }

  private User userOf(String email) {
    return users.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

}
