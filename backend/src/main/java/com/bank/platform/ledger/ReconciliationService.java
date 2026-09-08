package com.bank.platform.ledger;

import com.bank.platform.ledger.JournalEntryRepository.CurrencyNet;
import com.bank.platform.ledger.JournalEntryRepository.DuplicateReversal;
import com.bank.platform.ledger.JournalEntryRepository.OperationGroup;
import com.bank.platform.ledger.JournalEntryRepository.OperationGap;
import com.bank.platform.ledger.JournalEntryRepository.OrphanReversal;
import com.bank.platform.ledger.JournalEntryRepository.UnbalancedEntry;
import com.bank.platform.ledger.JournalLineRepository.AccountDifference;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent reconciliation (V29). Derives what every customer balance
 * SHOULD be from the journal alone - never by comparing the balance table
 * with itself - and reports three distinct layers so that a green \"the books
 * add up\" is never mistaken for \"every transaction was justified\":\n"
 *\n"
 * <ul>\n"
 *   <li><b>Accounting consistency</b> ({@code balanced}) - every entry's\n"
 *       signed lines net to zero per currency, account projections equal\n"
 *       their lines, one journal per operation, no unexplained money.\n"
 *   <li><b>Operation relationships</b> ({@code operationConsistent}) - every\n"
 *       posted TRANSFER/DEPOSIT/REVERSAL instruction has its expected journal\n"
 *       counterpart (no settled operation without its entry).</li>\n"
 *   <li><b>Reversal integrity</b> ({@code reversalConsistent}) - every linked\n"
 *       reversal names an entry that exists, and no entry is reversed twice.\n"
 *       A wrong-but-balanced charge passes layer 1; these layers are what\n"
 *       keep an operator from trusting a merely balanced ledger.</li>\n"
 * </ul>\n"
 *\n"
 * <p>It only REPORTS: reconciliation never mutates financial history, so a\n"
 * deliberately corrupted projection (test) is detected and left exactly as it\n"
 * is for an operator to investigate.\n"
 */
@Service
public class ReconciliationService {

  private final JournalEntryRepository entries;
  private final JournalLineRepository lines;

  public ReconciliationService(JournalEntryRepository entries, JournalLineRepository lines) {
    this.entries = entries;
    this.lines = lines;
  }

  @Transactional(readOnly = true)
  public ReconciliationReport reconcile() {
    List<AccountDifferenceRow> differences = lines.accountDifferences().stream()
        .map(AccountDifferenceRow::of).toList();
    List<UnbalancedEntryRow> unbalanced = entries.unbalancedEntries().stream()
        .map(UnbalancedEntryRow::of).toList();
    List<DuplicateRow> duplicates = entries.duplicateOperations().stream()
        .map(DuplicateRow::of).toList();
    List<CurrencyNetRow> currencyNets = entries.currencyNets().stream()
        .map(CurrencyNetRow::of).toList();
    boolean balanced = differences.isEmpty() && unbalanced.isEmpty()
        && duplicates.isEmpty() && currencyNets.isEmpty();

    // Layer 2: posted operations without their journal counterpart.
    List<OperationGapRow> operationGaps = entries.journalLessPostedOperations().stream()
        .map(OperationGapRow::of).toList();
    boolean operationConsistent = operationGaps.isEmpty();

    // Layer 3: reversal-link integrity (orphans / duplicates).
    List<ReversalIssueRow> reversalIssues = new java.util.ArrayList<>();
    entries.orphanReversals().stream().map(ReversalIssueRow::orphan).forEach(reversalIssues::add);
    entries.duplicateReversals().stream().map(ReversalIssueRow::duplicate).forEach(reversalIssues::add);
    boolean reversalConsistent = reversalIssues.isEmpty();

    return new ReconciliationReport(
        balanced,
        operationConsistent,
        reversalConsistent,
        entries.count(),
        lines.count(),
        differences,
        unbalanced,
        duplicates,
        currencyNets,
        operationGaps,
        reversalIssues);
  }

  /** The operator-visible result. DTO-shaped so Jackson serializes the nested
   *  projections without touching the repository interfaces. */
  public record AccountDifferenceRow(String accountId, String balance, String expected) {
    static AccountDifferenceRow of(AccountDifference d) {
      return new AccountDifferenceRow(d.getAccountId(),
          d.getBalance().toPlainString(), d.getExpected().toPlainString());
    }
  }

  public record UnbalancedEntryRow(String entryId, String kind, String operationRef, String net) {
    static UnbalancedEntryRow of(UnbalancedEntry e) {
      return new UnbalancedEntryRow(e.getEntryId(), e.getKind(),
          e.getOperationRef(), e.getNet().toPlainString());
    }
  }

  public record DuplicateRow(String kind, String operationRef, String count) {
    static DuplicateRow of(OperationGroup g) {
      return new DuplicateRow(g.getKind(), g.getOperationRef(), String.valueOf(g.getCount()));
    }
  }

  public record CurrencyNetRow(String currency, String net) {
    static CurrencyNetRow of(CurrencyNet c) {
      return new CurrencyNetRow(c.getCurrency(), c.getNet().toPlainString());
    }
  }

  public record OperationGapRow(String transactionId, String kind) {
    static OperationGapRow of(OperationGap g) {
      return new OperationGapRow(g.getTransactionId(), g.getKind());
    }
  }

  public record ReversalIssueRow(String kind, String reference, String detail) {
    static ReversalIssueRow orphan(OrphanReversal o) {
      return new ReversalIssueRow("ORPHAN_REVERSAL", o.getEntryId(),
          "reversal entry " + o.getEntryId() + " (ref " + o.getOperationRef()
              + ") links to missing entry " + o.getReverses());
    }

    static ReversalIssueRow duplicate(DuplicateReversal d) {
      return new ReversalIssueRow("DUPLICATE_REVERSAL", d.getOriginal(),
          "entry " + d.getOriginal() + " is reversed " + d.getCount() + " times");
    }
  }

  public record ReconciliationReport(
      boolean balanced,
      boolean operationConsistent,
      boolean reversalConsistent,
      long journalEntries,
      long journalLines,
      List<AccountDifferenceRow> projectionDifferences,
      List<UnbalancedEntryRow> unbalancedEntries,
      List<DuplicateRow> duplicateOperations,
      List<CurrencyNetRow> currencyNets,
      List<OperationGapRow> operationGaps,
      List<ReversalIssueRow> reversalIssues) {}
}
