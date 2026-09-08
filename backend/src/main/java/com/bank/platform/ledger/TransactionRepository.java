package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
      + "and t.status = :status and t.postedAt >= :since order by t.postedAt asc")
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
  //
  // a statement is a window of money that MOVED, so the bounds and the
  // ordering cut on posted_at - never created_at, which is the request time
  // and can precede settlement across a month boundary. Rows that never
  // posted (HELD/CANCELLED) have no posted_at and can never match.
  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.status = :status and t.postedAt >= :from and t.postedAt < :to "
      + "order by t.postedAt asc, t.seq asc")
  List<Transaction> postedRowsBetween(UUID accountId, TxStatus status, Instant from, Instant to);

  /** Settled rows over an inclusive-day {@link Period}, oldest-first. */
  default List<Transaction> statementRows(UUID accountId, Period period) {
    return postedRowsBetween(accountId, TxStatus.POSTED, period.start(), period.endExclusive());
  }

  /** Count of settled rows in the window - the statement size guard. */
  @Query("select count(t) from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) "
      + "and t.status = :status and t.postedAt >= :from and t.postedAt < :to")
  long countPostedBetween(UUID accountId, TxStatus status, Instant from, Instant to);

  /** Settled-row count over an inclusive-day {@link Period}. */
  default long postedCount(UUID accountId, Period period) {
    return countPostedBetween(accountId, TxStatus.POSTED, period.start(), period.endExclusive());
  }

  // Rows tied on created_at resolve newest-inserted-first via the DB-assigned
  // seq column (the random UUID id cannot express insertion order - V14).
  // history pages with a KEYSET cursor over the immutable seq identity,
  // never an OFFSET - an offset re-scans from the newest row every time, so a
  // row committed between two reads shifts everything and the reader
  // duplicates or skips it. A null cursorSeq means the first page (no bound);
  // the keyset predicate is seq < cursor, so the fetched page is exactly the
  // next {@code limit} rows under the same ordering. Ordering by seq alone
  // (not created_at) keeps the page key precise: created_at is shared by rows
  // flushed together and can round-trip differently through timestamp
  // columns, while seq is a total, immutable, database-assigned order.
  @Query(value = "SELECT * FROM ("
      + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
      + "UNION ALL "
      + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
      + ") u WHERE (CAST(:from AS timestamp with time zone) IS NULL OR u.created_at >= :from) "
      + "AND (CAST(:to AS timestamp with time zone) IS NULL OR u.created_at < :to) "
      + "AND (CAST(:cursorSeq AS bigint) IS NULL OR u.seq < :cursorSeq) "
      + "ORDER BY u.seq DESC LIMIT :limit",
      nativeQuery = true)
  List<Transaction> historyAfterCursor(UUID accountId, Instant from, Instant to,
      Long cursorSeq, int limit);

  /** One page of history over an inclusive-day {@link Period}, newest-first. */
  default List<Transaction> historyPage(UUID accountId, Period period, Long cursorSeq, int limit) {
    return historyAfterCursor(accountId, period.start(), period.endExclusive(), cursorSeq, limit);
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
    // The posting instant (JPQL alias postedAt below). Named for what it IS,
    // never after request time: a settled row can post long after it was
    // created, and this report cuts and buckets on the day money moved.
    Instant getPostedAt();
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
  @Query("select t.postedAt as postedAt, t.kind as kind, t.amount as amount, "
      + "t.fromAccountId as fromAccountId, t.toAccountId as toAccountId "
      + "from Transaction t where t.postedAt >= :since and t.status = :status "
      + "order by t.postedAt asc")
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
      + "and t.status = :status and t.postedAt >= :after")
  Optional<BigDecimal> sumSettledMovementAfter(@Param("accountId") UUID accountId,
      @Param("status") TxStatus status, @Param("after") Instant after);

  /**
   * Atomically resolves a held transfer: moves it out of HELD into {@code to}
   * (POSTED on approval, CANCELLED on decline), marks it reviewed and stamps
   * the posting time. Returns 1 for the operator who won the race,
   * 0 when someone already resolved it - so two concurrent approvals can
   * never both settle the same money. {@code postedAt} is set only when
   * {@code to == POSTED} (null for a decline, leaving the column null); the
   * CHECK requires every POSTED row to carry one, so the flip and the stamp
   * happen in the same UPDATE.
   */
  @Modifying
  @Query("update Transaction t set t.status = :to, t.reviewed = true, t.postedAt = :postedAt "
      + "where t.id = :id and t.status = :from")
  int resolveAwaitingReview(@Param("id") UUID id, @Param("from") TxStatus from, @Param("to") TxStatus to,
      @Param("postedAt") Instant postedAt);

  /**
   * Deposit idempotency keys are scoped to the account they fund (unique on
   * (to_account_id, idempotency_key) for rows with no originator - V20), so a
   * foreign key can never surface another user's deposit.
   */
  Optional<Transaction> findFirstByIdempotencyKeyAndToAccountIdAndFromAccountIdIsNull(
      String idempotencyKey, UUID toAccountId);

  /**
   * Operation-status lookup scoped to the ORIGINATOR: the user whose
   * account carries the key namespace. A deposit's originator is its funded
   * account (from_account_id IS NULL); a transfer's is its sender. Rows the
   * caller merely received are not their operations.
   */
  @Query("select t from Transaction t where t.idempotencyKey = :key "
      + "and ((t.fromAccountId is not null and t.fromAccountId in :owned) "
      + "or (t.fromAccountId is null and t.toAccountId in :owned))")
  List<Transaction> findOperationsByKey(@Param("key") String key, @Param("owned") List<UUID> owned);

  /**
   * Operation lookup scoped to ONE originating account (namespace fix):
   * the database uniqueness lives on (from_account_id, key) for transfers and
   * (to_account_id, key) for deposits, so restricting the lookup to the
   * originating account makes the replay/lookup namespace exactly the
   * uniqueness namespace - at most one row can ever match.
   */
  @Query("select t from Transaction t where t.idempotencyKey = :key "
      + "and ((t.fromAccountId is not null and t.fromAccountId = :accountId) "
      + "or (t.fromAccountId is null and t.toAccountId = :accountId))")
  Optional<Transaction> findOperationByKeyAndAccount(@Param("key") String key,
      @Param("accountId") UUID accountId);

  /**
   * Bounded, newest-first recovery list of the originator's own keyed
   * operations (transfers + deposits only - the engine never keys its own
   * rows) created since {@code since}. Completed-but-unacknowledged postings
   * are deliberately included: losing the response must not lose the
   * financial record, so an owner can always rediscover what a key did even
   * when their browser storage was cleared at logout.
   */
  @Query("select t from Transaction t where t.idempotencyKey is not null "
      + "and t.kind in :kinds "
      + "and ((t.fromAccountId is not null and t.fromAccountId in :owned) "
      + "or (t.fromAccountId is null and t.toAccountId in :owned)) "
      + "and t.createdAt >= :since order by t.createdAt desc, t.seq desc")
  List<Transaction> findRecentOperationsByOwner(@Param("owned") List<UUID> owned,
      @Param("kinds") List<TxKind> kinds, @Param("since") Instant since,
      Pageable pageable);

  /**
   * Raw DB-assigned seq of one row, read straight from the table. The paging
   * query maps entities, and an entity that is already in the persistence
   * context keeps its in-memory state - where {@code seq} is still null
   * because the identity value is assigned by the database, never written
   * back to the object. The keyset cursor must carry the real ordering key,
   * so the boundary row's seq comes from this column read instead.
   */
  @Query(value = "SELECT seq FROM transactions WHERE id = :id", nativeQuery = true)
  Optional<Long> rawSeqOf(UUID id);

  /**
   * public transfer numbers classify by KIND and posted status - never
   * by "has a from side". The interest engine posts loan charges with a from
   * side and no to side; counting {@code from_account_id IS NOT NULL} rows
   * would present engine interest as user transfers. A public transfer is a
   * row the banking rail labelled TRANSFER that actually posted; HELD and
   * CANCELLED instructions never moved money and are excluded by status, and
   * DEPOSIT/INTEREST rows are excluded by kind.
   */
  @Query("select count(t) from Transaction t where t.kind = :kind and t.status = :status")
  long countByKindAndStatus(@Param("kind") TxKind kind, @Param("status") TxStatus status);

  /** Duplicate-reversal protection: at most one reversal per original instruction. */
  boolean existsByReversesTransactionId(UUID reversesTransactionId);

  /** Reversal rows whose original is one of {@code originalIds}. */
  List<Transaction> findByReversesTransactionIdIn(Collection<UUID> originalIds);

  /**
   * original-id → its reversal row id, for the given rows (empty when none
   * have been reversed). The operator surface uses this so a console never
   * offers a second reversal of a row that already has one - the original row
   * itself is untouched (history stays as it was), so only the reversal index
   * can say "this has been reversed".
   */
  default Map<UUID, UUID> reversalIndexBy(Collection<Transaction> rows) {
    List<UUID> ids = rows.stream().map(Transaction::getId).toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    Map<UUID, UUID> index = new HashMap<>();
    for (Transaction reversal : findByReversesTransactionIdIn(ids)) {
      index.put(reversal.getReversesTransactionId(), reversal.getId());
    }
    return index;
  }

  @Query("select coalesce(sum(t.amount), 0) from Transaction t where t.kind = :kind and t.status = :status")
  Optional<BigDecimal> sumAmountByKindAndStatus(@Param("kind") TxKind kind, @Param("status") TxStatus status);

}

