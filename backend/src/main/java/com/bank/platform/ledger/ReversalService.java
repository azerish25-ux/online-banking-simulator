package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.accounts.AccountStatus;
import com.bank.platform.accounts.AccountType;
import com.bank.platform.auth.Role;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.common.LedgerCacheInvalidation;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authorized reversal workflow (V29).
 *
 * <p>A posted instruction is never edited or deleted: history is history. An
 * operator reverses it by authoring a NEW {@code REVERSAL} transaction that
 * moves the money back along the original legs, and a linked {@code REVERSAL}
 * journal entry whose {@code reverses_entry_id} names the original entry. The
 * original row keeps its POSTED state and its own history: a
 * posted-then-reversed transfer is never relabelled as if it had never
 * settled.
 *
 * <ul>
 *   <li><b>One reversal per original.</b> The row carries
 *       {@code reverses_transaction_id} (unique per original on PostgreSQL;
 *       existence-checked here on H2) and the journal forbids reversing an
 *       already-reversed entry.</li>
 *   <li><b>A reason and an actor.</b> The operator's reason is preserved on
 *       the reversal row; the actor is audited with the reason. A reason is
 *       mandatory.</li>
 *   <li><b>Current-state honesty.</b> Reversal moves real money under the
 *       same locks and rules as any transfer: if the payee has spent the
 *       funds, or a loan's current state cannot accept the repayment, the
 *       reversal is declined with the underlying reason: never forced
 *       through, never silently dropped.</li>
 * </ul>
 */
@Service
public class ReversalService {

  private final UserRepository users;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;
  private final LedgerMovementService movement;
  private final PrincipalMovementRepository principalMovements;
  private final JournalService journal;
  private final JournalEntryRepository journalEntries;
  private final LedgerEventsService events;
  private final Clock clock;
  private final LedgerCacheInvalidation invalidation;

  public ReversalService(UserRepository users, AccountRepository accounts,
      TransactionRepository transactions, LedgerMovementService movement,
      PrincipalMovementRepository principalMovements, JournalService journal,
      JournalEntryRepository journalEntries, LedgerEventsService events, Clock clock,
      LedgerCacheInvalidation invalidation) {
    this.users = users;
    this.accounts = accounts;
    this.transactions = transactions;
    this.movement = movement;
    this.principalMovements = principalMovements;
    this.journal = journal;
    this.journalEntries = journalEntries;
    this.events = events;
    this.clock = clock;
    this.invalidation = invalidation;
  }

  /**
   * Reverses a posted TRANSFER or DEPOSIT. Returns the new REVERSAL row.
   *
   * @throws TransferValidationException when the instruction is not reversible
   *     (HELD/CANCELLED/INTEREST/legacy-pre-journal/already-reversed), the
   *     reason is missing, or the current account state cannot absorb the
   *     reverse movement.
   */
  @Transactional
  public Transaction reverse(String actorEmail, UUID transactionId, String reason) {
    User actor = operatorOf(actorEmail);
    String cleanReason = reason == null ? "" : reason.trim();
    if (cleanReason.isBlank()) {
      throw new TransferValidationException("A reversal reason is required");
    }
    if (cleanReason.length() > 255) {
      throw new TransferValidationException("Reversal reason must be at most 255 characters");
    }

    Transaction original = transactions.findById(transactionId)
        .orElseThrow(() -> new TransactionNotFoundException(transactionId));
    if (original.getStatus() != TxStatus.POSTED) {
      throw new TransferValidationException(
          "Only POSTED transactions can be reversed (HELD and CANCELLED never moved money)");
    }
    if (original.getKind() == TxKind.INTEREST) {
      throw new TransferValidationException(
          "Engine interest is not reversible through this workflow; correct the account by transfer");
    }
    if (original.getKind() == TxKind.REVERSAL) {
      throw new TransferValidationException("A reversal cannot itself be reversed");
    }
    if (original.getReversesTransactionId() != null) {
      throw new TransferValidationException("This row is already a reversal");
    }
    // Duplicate-reversal protection: one reversal per original instruction.
    if (transactions.existsByReversesTransactionId(original.getId())) {
      throw new TransferValidationException("This transaction has already been reversed");
    }
    // The linked journal entry must exist: a posted transfer/deposit predating
    // the reconciled journal (V22) has no reversible accounting record, so the
    // workflow refuses instead of guessing at history.
    JournalEntry originalEntry = journalEntries.findByKindAndOperationRef(
        original.getKind() == TxKind.DEPOSIT ? JournalKind.DEPOSIT : JournalKind.TRANSFER,
        original.getId().toString());
    if (originalEntry == null) {
      throw new TransferValidationException(
          "This transaction predates the reconciled journal and cannot be reversed by this workflow");
    }

    Instant now = clock.instant();
    Transaction reversal = new Transaction();
    reversal.setAmount(original.getAmount());
    reversal.setCurrency("USD");
    reversal.setKind(TxKind.REVERSAL);
    reversal.setStatus(TxStatus.POSTED);
    reversal.setCreatedAt(now);
    reversal.setPostedAt(now);
    reversal.setReversesTransactionId(original.getId());
    reversal.setReversalReason(cleanReason);

    if (original.getKind() == TxKind.DEPOSIT) {
      return reverseDeposit(actor, original, originalEntry, reversal, now);
    }
    return reverseTransfer(actor, original, originalEntry, reversal, now);
  }

  /** A transfer reverses along its own legs: the payee pays the money back. */
  private Transaction reverseTransfer(User actor, Transaction original, JournalEntry originalEntry,
      Transaction reversal, Instant now) {
    UUID payee = original.getToAccountId();
    UUID payer = original.getFromAccountId();
    reversal.setFromAccountId(payee);
    reversal.setToAccountId(payer);
    // move() takes the ID-ordered locks and enforces ACTIVE + affordability on
    // the payee: if the funds have been spent (or a loan's current state
    // cannot accept the reverse movement), the reversal is declined with the
    // underlying validation reason: never forced through.
    LedgerMovementService.Moved moved = movement.move(payee, payer, original.getAmount());
    Account from = moved.from();
    Account to = moved.to();
    try {
      transactions.saveAndFlush(reversal);
    } catch (DataIntegrityViolationException race) {
      // The unique (reverses_transaction_id) index won the race for another
      // operator's simultaneous reversal of the same original.
      throw new TransferValidationException("This transaction has already been reversed");
    }
    for (LedgerMovementService.PrincipalEvent event : moved.principalEvents()) {
      principalMovements.save(new PrincipalMovement(event.accountId(), event.kind(),
          event.signedAmount(), reversal.getId(), now));
    }
    journal.post(JournalKind.REVERSAL, reversal.getId().toString(), now,
        "Reversal of " + original.getId() + ": " + reversal.getReversalReason(),
        originalEntry.getId(),
        JournalService.Posting.account(from.getId(), original.getAmount().negate()),
        JournalService.Posting.account(to.getId(), original.getAmount()));
    events.transactionReversed(actor, original, reversal, from, to);
    invalidation.clearSynchronized("summaries", "public-stats");
    return reversal;
  }

  /**
   * A deposit reverses back to the funding rail: the credited account is
   * debited, and the balancing side is the simulator-funding counteraccount.
   * The depositor must still hold the funds (affordability), the account must
   * be ACTIVE, and a deposit that repaid a LOAN is refused: it already
   * extinguished interest/principal under the repayment allocation, so
   * reversing it would require re-creating debt the workflow cannot prove.
   */
  private Transaction reverseDeposit(User actor, Transaction original, JournalEntry originalEntry,
      Transaction reversal, Instant now) {
    UUID accountId = original.getToAccountId();
    Account account = accounts.findByIdForUpdate(accountId)
        .orElseThrow(() -> new TransactionNotFoundException(accountId));
    reversal.setFromAccountId(accountId);
    reversal.setToAccountId(null);
    if (account.getStatus() != AccountStatus.ACTIVE) {
      throw new TransferValidationException("Account " + account.getIban() + " is not active");
    }
    if (account.getType() == AccountType.LOAN) {
      throw new TransferValidationException(
          "A deposit that repaid a loan cannot be reversed; move the money with a transfer instead");
    }
    if (account.getBalance().subtract(original.getAmount()).signum() < 0) {
      throw new com.bank.platform.ledger.InsufficientFundsException();
    }
    account.setBalance(account.getBalance().subtract(original.getAmount()));
    accounts.save(account);
    try {
      transactions.saveAndFlush(reversal);
    } catch (DataIntegrityViolationException race) {
      throw new TransferValidationException("This transaction has already been reversed");
    }
    journal.post(JournalKind.REVERSAL, reversal.getId().toString(), now,
        "Reversal of " + original.getId() + ": " + reversal.getReversalReason(),
        originalEntry.getId(),
        JournalService.Posting.account(account.getId(), original.getAmount().negate()),
        JournalService.Posting.counter(JournalLine.SIMULATOR_FUNDING, original.getAmount()));
    events.depositReversed(actor, original, reversal, account);
    invalidation.clearSynchronized("summaries", "public-stats");
    return reversal;
  }

  private User operatorOf(String adminEmail) {
    User admin = users.findByEmail(adminEmail)
        .orElseThrow(() -> new UsernameNotFoundException("Admin not found"));
    if (admin.getRole() != Role.ADMIN) {
      throw new AccessDeniedException("Admins only");
    }
    return admin;
  }
}
