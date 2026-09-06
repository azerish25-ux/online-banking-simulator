package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The ONLY writer of journal entries (F15). Every posted business operation
 * calls {@link #post} inside its own transaction, so the operation's status
 * flip, its journal postings, the balance projection updates, the audit row
 * and the notification all commit (or all roll back) together.
 *
 * <p>Sign convention: a line's {@code amount} is a signed delta added to the
 * named side. For a customer account the balance projection is
 * {@code balance + amount} - deposits and savings interest are positive,
 * transfers out and loan interest charges are negative. Every entry carries
 * at least two postings whose signed amounts sum to zero per currency; the
 * single-currency ledger is USD, so a customer credit is always balanced by a
 * counteraccount debit (SIMULATOR_FUNDING for the funding rail, INTEREST for
 * interest postings, MIGRATION_OPENING for cutover entries). Money can never
 * appear in a customer balance without a balancing posting on the other side.
 *
 * <p>HELD and CANCELLED instructions create NO entry - they never settled and
 * no money moved. A correction is a new authorized entry linked via
 * {@code reversesEntryId}, never an edit of an existing posting.
 */
@Service
public class JournalService {

  private static final Set<String> COUNTERACCOUNTS = Set.of(
      JournalLine.SIMULATOR_FUNDING, JournalLine.INTEREST, JournalLine.MIGRATION_OPENING);

  private final JournalEntryRepository entries;
  private final JournalLineRepository lines;

  public JournalService(JournalEntryRepository entries, JournalLineRepository lines) {
    this.entries = entries;
    this.lines = lines;
  }

  /** One side of a posting: exactly one of account/counteraccount is set. */
  public record Posting(UUID accountId, String counteraccount, BigDecimal amount) {
    public static Posting account(UUID accountId, BigDecimal amount) {
      return new Posting(accountId, null, amount);
    }

    public static Posting counter(String counteraccount, BigDecimal amount) {
      return new Posting(null, counteraccount, amount);
    }
  }

  /**
   * Books one balanced journal entry for a posted operation. Validates the
   * accounting invariants BEFORE writing anything: at least two non-zero
   * postings, each naming exactly one side, netting to zero per currency.
   */
  public JournalEntry post(JournalKind kind, String operationRef, Instant postedAt,
      String memo, Posting... postings) {
    if (kind == null) {
      throw new TransferValidationException("Journal kind is required");
    }
    if (operationRef == null || operationRef.isBlank()) {
      throw new TransferValidationException("Journal operation reference is required");
    }
    if (postings == null || postings.length < 2) {
      throw new TransferValidationException("A journal entry needs at least two postings");
    }
    BigDecimal net = BigDecimal.ZERO;
    for (Posting posting : postings) {
      validate(posting);
      net = net.add(posting.amount());
    }
    if (net.signum() != 0) {
      throw new TransferValidationException(
          "Journal entry would not balance (net " + net.toPlainString() + ")");
    }

    JournalEntry entry = new JournalEntry(kind, operationRef, "USD", postedAt, memo);
    entries.saveAndFlush(entry);
    List<JournalLine> rows = new ArrayList<>(postings.length);
    for (int i = 0; i < postings.length; i++) {
      Posting posting = postings[i];
      JournalLine row = posting.accountId() != null
          ? JournalLine.account(entry.getId(), posting.accountId(), posting.amount(), i, postedAt)
          : JournalLine.counter(entry.getId(), posting.counteraccount(), posting.amount(), i, postedAt);
      rows.add(row);
    }
    lines.saveAll(rows);
    return entry;
  }

  private void validate(Posting posting) {
    if (posting == null || posting.amount() == null) {
      throw new TransferValidationException("A journal posting needs an amount");
    }
    boolean accountSide = posting.accountId() != null;
    boolean counterSide = posting.counteraccount() != null;
    if (accountSide == counterSide) {
      throw new TransferValidationException(
          "A journal posting names exactly one side: an account or a counteraccount");
    }
    if (counterSide && !COUNTERACCOUNTS.contains(posting.counteraccount())) {
      throw new TransferValidationException("Unknown counteraccount: " + posting.counteraccount());
    }
    if (posting.amount().signum() == 0) {
      throw new TransferValidationException("A journal posting amount must be non-zero");
    }
  }
}
