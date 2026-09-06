package com.bank.platform.ledger;

import com.bank.platform.ledger.JournalEntryRepository.CurrencyNet;
import com.bank.platform.ledger.JournalEntryRepository.OperationGroup;
import com.bank.platform.ledger.JournalEntryRepository.UnbalancedEntry;
import com.bank.platform.ledger.JournalLineRepository.AccountDifference;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Independent reconciliation (F15). Derives what every customer balance
 * SHOULD be from the journal alone - never by comparing the balance table
 * with itself - and reports:
 *
 * <ul>
 *   <li><b>per-currency balancing</b> - every entry's signed lines must net to
 *       zero (and therefore the whole journal does);</li>
 *   <li><b>projection differences</b> - account.balance vs. the sum of its
 *       journal lines (a balance changed without a posting, or a posting
 *       whose projection update was lost, shows up here);</li>
 *   <li><b>duplicate operation entries</b> - one operation journaled twice
 *       (also impossible by the DB unique constraint - the check proves it);</li>
 *   <li><b>unexplained movements</b> - any non-zero per-currency net is money
 *       that appeared or vanished without a balancing entry.</li>
 * </ul>
 *
 * <p>It only REPORTS: reconciliation never mutates financial history, so a
 * deliberately corrupted projection (test) is detected and left exactly as it
 * is for an operator to investigate.
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
    boolean balanced = true;
    List<AccountDifferenceRow> differences = lines.accountDifferences().stream()
        .map(AccountDifferenceRow::of).toList();
    List<UnbalancedEntryRow> unbalanced = entries.unbalancedEntries().stream()
        .map(UnbalancedEntryRow::of).toList();
    List<DuplicateRow> duplicates = entries.duplicateOperations().stream()
        .map(DuplicateRow::of).toList();
    List<CurrencyNetRow> currencyNets = entries.currencyNets().stream()
        .map(CurrencyNetRow::of).toList();
    balanced = differences.isEmpty() && unbalanced.isEmpty()
        && duplicates.isEmpty() && currencyNets.isEmpty();
    return new ReconciliationReport(
        balanced,
        entries.count(),
        lines.count(),
        differences,
        unbalanced,
        duplicates,
        currencyNets);
  }

  /**
   * The operator-visible result. DTO-shaped so Jackson serializes the nested
   * projections without touching the repository interfaces.
   */
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

  public record ReconciliationReport(
      boolean balanced,
      long journalEntries,
      long journalLines,
      List<AccountDifferenceRow> projectionDifferences,
      List<UnbalancedEntryRow> unbalancedEntries,
      List<DuplicateRow> duplicateOperations,
      List<CurrencyNetRow> currencyNets) {}
}
