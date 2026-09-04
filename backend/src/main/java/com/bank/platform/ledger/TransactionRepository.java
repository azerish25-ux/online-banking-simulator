package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository
    extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

  /**
   * Idempotency keys are scoped to their originating account (unique on
   * (from_account_id, idempotency_key) in the DB), so a foreign key can never
   * surface another user's row.
   */
  Optional<Transaction> findByFromAccountIdAndIdempotencyKey(UUID fromAccountId, String idempotencyKey);

  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) and t.createdAt >= :since order by t.createdAt asc")
  List<Transaction> findByAccountSince(UUID accountId, Instant since);

  /**
   * Settled rows only - HELD intents and CANCELLED transfers never moved
   * money, so summaries and other money-movement reads must exclude them.
   */
  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.status = :status and t.createdAt >= :since order by t.createdAt asc")
  List<Transaction> findSettledByAccountSince(UUID accountId, TxStatus status, Instant since);

  // Statements read oldest-first like a paper bank statement (history - the
  // interactive feed - stays newest-first). The id tiebreak keeps rows created
  // in the same instant in a stable order.
  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.createdAt >= :from and t.createdAt < :to order by t.createdAt asc, t.id asc")
  List<Transaction> statementRows(UUID accountId, Instant from, Instant to);

  @Query(value = "SELECT * FROM ("
      + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
      + "UNION ALL "
      + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
      + ") u WHERE u.created_at >= :from AND u.created_at < :to "
      + "ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<Transaction> historyPage(UUID accountId, Instant from, Instant to, int limit, int offset);

  @Query(value = "SELECT COUNT(*) FROM transactions t "
      + "WHERE (t.from_account_id = :accountId OR t.to_account_id = :accountId) "
      + "AND t.created_at >= :from AND t.created_at < :to",
      nativeQuery = true)
  long historyCount(UUID accountId, java.time.Instant from, java.time.Instant to);

  @Query("select t from Transaction t where t.createdAt >= :since order by t.createdAt asc")
  List<Transaction> findSince(Instant since);

  /** Columns the daily-totals report actually buckets on - no full entities. */
  interface PostedRow {
    Instant getCreatedAt();
    TxKind getKind();
    BigDecimal getAmount();
    UUID getFromAccountId();
    UUID getToAccountId();
  }

  /**
   * Settled rows since {@code since}, projected to the report's needs. The
   * status and window filters run in SQL, so the JVM never materializes every
   * transaction entity (with memo, currency, ...) just to sum a few columns.
   */
  @Query("select t.createdAt as createdAt, t.kind as kind, t.amount as amount, "
      + "t.fromAccountId as fromAccountId, t.toAccountId as toAccountId "
      + "from Transaction t where t.createdAt >= :since and t.status = :status "
      + "order by t.createdAt asc")
  List<PostedRow> findPostedSince(@Param("since") Instant since, @Param("status") TxStatus status);

  /**
   * Atomically resolves a held transfer: moves it out of HELD into {@code to}
   * (POSTED on approval, CANCELLED on decline) and marks it reviewed. Returns
   * 1 for the operator who won the race, 0 when someone already resolved it -
   * so two concurrent approvals can never both settle the same money.
   */
  @Modifying
  @Query("update Transaction t set t.status = :to, t.reviewed = true "
      + "where t.id = :id and t.status = :from")
  int resolveAwaitingReview(@Param("id") UUID id, @Param("from") TxStatus from, @Param("to") TxStatus to);

  /** Only settled (POSTED) rows count as transfers - HELD rows are intents, not money moved. */
  @Query("select count(t) from Transaction t where t.fromAccountId is not null and t.status = :status")
  long countSettledTransfers(@Param("status") TxStatus status);

  @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.fromAccountId is not null and t.status = :status")
  Optional<BigDecimal> sumSettledTransferVolume(@Param("status") TxStatus status);

}

