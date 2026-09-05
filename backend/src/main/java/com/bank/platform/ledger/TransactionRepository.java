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
  // interactive feed - stays newest-first). Rows persisted in one flush share
  // created_at; the monotonic seq tiebreak keeps them in real insertion order
  // (the UUID id is random and cannot express it - see V14).
  //
  // The half-open instant pair is the SQL floor; the day-window rule lives in
  // Period - statementRows/historyPage/historyCount take an inclusive-day
  // Period and derive the bounds, so callers cannot drift "to is inclusive"
  // from the query bound again. Statements are always closed windows (the
  // service defaults either side), so statementRowsBetween stays strict;
  // history is genuinely open-ended, and its null-tolerant guards carry an
  // explicit CAST because PostgreSQL cannot infer a parameter's type from an
  // IS NULL comparison alone (SQLState 42P18) when the bound is a bare null.
  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.createdAt >= :from and t.createdAt < :to order by t.createdAt asc, t.seq asc")
  List<Transaction> statementRowsBetween(UUID accountId, Instant from, Instant to);

  /** Statement rows over an inclusive-day {@link Period}, oldest-first. */
  default List<Transaction> statementRows(UUID accountId, Period period) {
    return statementRowsBetween(accountId, period.start(), period.endExclusive());
  }

  // Rows tied on created_at resolve newest-inserted-first via the DB-assigned
  // seq column (the random UUID id cannot express insertion order - V14).
  @Query(value = "SELECT * FROM ("
      + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
      + "UNION ALL "
      + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
      + ") u WHERE (CAST(:from AS timestamp with time zone) IS NULL OR u.created_at >= :from) "
      + "AND (CAST(:to AS timestamp with time zone) IS NULL OR u.created_at < :to) "
      + "ORDER BY u.created_at DESC, u.seq DESC LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  List<Transaction> historyPageBetween(UUID accountId, Instant from, Instant to, int limit, int offset);

  /** One page of history over an inclusive-day {@link Period}, newest-first. */
  default List<Transaction> historyPage(UUID accountId, Period period, int limit, int offset) {
    return historyPageBetween(accountId, period.start(), period.endExclusive(), limit, offset);
  }

  @Query(value = "SELECT COUNT(*) FROM transactions t "
      + "WHERE (t.from_account_id = :accountId OR t.to_account_id = :accountId) "
      + "AND (CAST(:from AS timestamp with time zone) IS NULL OR t.created_at >= :from) "
      + "AND (CAST(:to AS timestamp with time zone) IS NULL OR t.created_at < :to)",
      nativeQuery = true)
  long historyCountBetween(UUID accountId, Instant from, Instant to);

  /** Row count for an inclusive-day {@link Period} (the window cap guard). */
  default long historyCount(UUID accountId, Period period) {
    return historyCountBetween(accountId, period.start(), period.endExclusive());
  }

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
   * Signed net movement with createdAt at/after {@code after} (credits
   * positive, debits negative) - the ONE owner of the +to/-from sign
   * convention. The statement renderer derives both balance figures from it:
   * current balance minus this sum at the window's start instant is the
   * true opening, and at the window's exclusive end it is the closing - a
   * statement for a past period never prints today's balance as its closing.
   */
  @Query("select coalesce(sum(case when t.toAccountId = :accountId then t.amount else -t.amount end), 0) "
      + "from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.status = :status and t.createdAt >= :after")
  Optional<BigDecimal> sumSettledMovementAfter(@Param("accountId") UUID accountId,
      @Param("status") TxStatus status, @Param("after") Instant after);

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

