package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

  long countByKindAndOperationRef(JournalKind kind, String operationRef);

  long countByKind(JournalKind kind);

  /** The one entry a posted operation produced ((kind, operation_ref) is unique). */
  JournalEntry findByKindAndOperationRef(JournalKind kind, String operationRef);

  /** Whether an original entry already has a linked reversal (one per original). */
  long countByReversesEntryId(UUID reversesEntryId);

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

  /**
   * Orphaned reversal links - a reversal entry whose original entry no longer
   * exists (append-only tables make this impossible by construction; the
   * check proves it and flags any tampering or migration defect).
   */
  @Query(value = "SELECT CAST(e.id AS VARCHAR) AS entryId, e.kind AS kind, "
      + "e.operation_ref AS operationRef, CAST(e.reverses_entry_id AS VARCHAR) AS reverses "
      + "FROM journal_entries e WHERE e.reverses_entry_id IS NOT NULL "
      + "AND NOT EXISTS (SELECT 1 FROM journal_entries o WHERE o.id = e.reverses_entry_id)",
      nativeQuery = true)
  List<OrphanReversal> orphanReversals();

  interface OrphanReversal {
    String getEntryId();
    String getKind();
    String getOperationRef();
    String getReverses();
  }

  /**
   * Duplicate reversal links - two entries claiming the same original. The
   * one-reversal-per-original rule forbids it; the check proves it.
   */
  @Query(value = "SELECT CAST(e.reverses_entry_id AS VARCHAR) AS original, COUNT(*) AS count "
      + "FROM journal_entries e WHERE e.reverses_entry_id IS NOT NULL "
      + "GROUP BY e.reverses_entry_id HAVING COUNT(*) > 1",
      nativeQuery = true)
  List<DuplicateReversal> duplicateReversals();

  interface DuplicateReversal {
    String getOriginal();
    Long getCount();
  }

  /**
   * Posted TRANSFER/DEPOSIT/REVERSAL transactions with NO journal entry - an
   * operation that settled without its accounting counterpart (the DB unique
   * on (kind, operation_ref) and the transaction-boundary enforcement make
   * this impossible in normal flow; the check proves it to an operator).
   */
  @Query(value = "SELECT CAST(t.id AS VARCHAR) AS transactionId, t.kind AS kind "
      + "FROM transactions t WHERE t.status = 'POSTED' AND t.kind IN "
      + "('TRANSFER', 'DEPOSIT', 'REVERSAL') "
      + "AND NOT EXISTS (SELECT 1 FROM journal_entries e "
      + "WHERE e.operation_ref = CAST(t.id AS VARCHAR)) "
      + "ORDER BY t.created_at",
      nativeQuery = true)
  List<OperationGap> journalLessPostedOperations();

  interface OperationGap {
    String getTransactionId();
    String getKind();
  }
}
