package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

  long countByKindAndOperationRef(JournalKind kind, String operationRef);

  long countByKind(JournalKind kind);

  /** One operation that produced more than one journal entry. */
  interface OperationGroup {
    String getKind();
    String getOperationRef();
    Long getCount();
  }

  /**
   * Operations that somehow produced more than one journal entry. The
   * (kind, operation_ref) unique constraint already forbids this - the check
   * exists so the reconciliation report can prove it and flag any regression.
   */
  @Query(value = "SELECT e.kind AS kind, e.operation_ref AS operationRef, COUNT(*) AS count "
      + "FROM journal_entries e GROUP BY e.kind, e.operation_ref HAVING COUNT(*) > 1",
      nativeQuery = true)
  List<OperationGroup> duplicateOperations();

  /** Per-currency net of ALL lines - must be empty (every entry balances). */
  @Query(value = "SELECT e.currency AS currency, SUM(l.amount) AS net "
      + "FROM journal_entries e JOIN journal_lines l ON l.entry_id = e.id "
      + "GROUP BY e.currency HAVING SUM(l.amount) <> 0",
      nativeQuery = true)
  List<CurrencyNet> currencyNets();

  interface CurrencyNet {
    String getCurrency();
    BigDecimal getNet();
  }

  /** Entries whose own lines do not sum to zero - a tampered or broken entry. */
  @Query(value = "SELECT CAST(e.id AS VARCHAR) AS entryId, e.kind AS kind, "
      + "e.operation_ref AS operationRef, "
      + "SUM(l.amount) AS net FROM journal_entries e "
      + "JOIN journal_lines l ON l.entry_id = e.id "
      + "GROUP BY e.id, e.kind, e.operation_ref HAVING SUM(l.amount) <> 0",
      nativeQuery = true)
  List<UnbalancedEntry> unbalancedEntries();

  interface UnbalancedEntry {
    String getEntryId();
    String getKind();
    String getOperationRef();
    BigDecimal getNet();
  }
}
