package com.bank.platform.ledger;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Server-backed history filters ( section 14: "do not filter only the
 * currently loaded ten rows and describe it as searching account history").
 * The customer feed's date window already ran in SQL; this DAO extends the
 * SAME native keyset query with amount range, kind(s), state(s) and a
 * reference/counterparty search - every filter is a SQL predicate over the
 * whole account history, so a page never answers a question the database was
 * not asked.
 *
 * <p>The base row set is the caller's own account legs (from OR to), exactly
 * as the unfiltered keyset query defines it; the search term matches the
 * memo (the customer's reference) OR the counter-party account's IBAN via a
 * LEFT JOIN restricted to the OTHER leg, so searching an IBAN you received
 * money from finds those rows without ever exposing another user's rows
 * (the caller can only ever see their own account's history). LIKE
 * wildcards in the term are escaped, and searches are case-insensitive.
 */
@Repository
public class TransactionHistoryDao {

  private final EntityManager em;

  public TransactionHistoryDao(EntityManager em) {
    this.em = em;
  }

  /** Validated customer-history filter set (null/empty members are open). */
  public record HistoryFilter(
      UUID accountId,
      Instant from,
      Instant to,
      BigDecimal minAmount,
      BigDecimal maxAmount,
      List<String> kinds,
      List<String> statuses,
      String searchTerm) {}

  private static final String BASE =
      "SELECT u.* FROM ("
          + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
          + "UNION ALL "
          + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
          + ") u "
          + "LEFT JOIN accounts a ON a.id <> :accountId "
          + "AND (a.id = u.from_account_id OR a.id = u.to_account_id) "
          + "WHERE (CAST(:from AS timestamp with time zone) IS NULL OR u.created_at >= :from) "
          + "AND (CAST(:to AS timestamp with time zone) IS NULL OR u.created_at < :to)";

  /**
   * One keyset page (newest-first, the {@code seq} identity the unfiltered
   * query pages on) over the FILTERED row set.
   */
  public List<Transaction> page(HistoryFilter filter, Long cursorSeq, int limit) {
    StringBuilder sql = new StringBuilder(BASE);
    List<Map.Entry<String, Object>> inParams = new ArrayList<>();
    append(sql, filter, inParams);
    if (cursorSeq != null) {
      sql.append(" AND u.seq < :cursorSeq");
    }
    sql.append(" ORDER BY u.seq DESC LIMIT :limit");

    Query query = em.createNativeQuery(sql.toString(), Transaction.class)
        .setParameter("accountId", filter.accountId())
        .setParameter("from", filter.from())
        .setParameter("to", filter.to());
    for (Map.Entry<String, Object> entry : inParams) {
      query.setParameter(entry.getKey(), entry.getValue());
    }
    if (cursorSeq != null) {
      query.setParameter("cursorSeq", cursorSeq);
    }
    query.setParameter("limit", limit);
    @SuppressWarnings("unchecked")
    List<Transaction> result = query.getResultList();
    return result;
  }

  /** Total matching rows under the same predicates (minus cursor/limit). */
  public long count(HistoryFilter filter) {
    StringBuilder sql = new StringBuilder(
        "SELECT count(*) FROM ("
            + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
            + "UNION ALL "
            + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
            + ") u "
            + "LEFT JOIN accounts a ON a.id <> :accountId "
            + "AND (a.id = u.from_account_id OR a.id = u.to_account_id) "
            + "WHERE (CAST(:from AS timestamp with time zone) IS NULL OR u.created_at >= :from) "
            + "AND (CAST(:to AS timestamp with time zone) IS NULL OR u.created_at < :to)");
    List<Map.Entry<String, Object>> inParams = new ArrayList<>();
    append(sql, filter, inParams);
    Query query = em.createNativeQuery(sql.toString())
        .setParameter("accountId", filter.accountId())
        .setParameter("from", filter.from())
        .setParameter("to", filter.to());
    for (Map.Entry<String, Object> entry : inParams) {
      query.setParameter(entry.getKey(), entry.getValue());
    }
    return ((Number) query.getSingleResult()).longValue();
  }

  /** Appends the optional predicates; IN lists expand to distinct placeholders. */
  private static void append(StringBuilder sql, HistoryFilter filter,
      List<Map.Entry<String, Object>> params) {
    if (filter.minAmount() != null) {
      sql.append(" AND u.amount >= CAST(:minAmount AS numeric)");
      params.add(Map.entry("minAmount", filter.minAmount()));
    }
    if (filter.maxAmount() != null) {
      sql.append(" AND u.amount <= CAST(:maxAmount AS numeric)");
      params.add(Map.entry("maxAmount", filter.maxAmount()));
    }
    if (filter.kinds() != null && !filter.kinds().isEmpty()) {
      sql.append(" AND u.kind IN (");
      for (int i = 0; i < filter.kinds().size(); i++) {
        sql.append(i == 0 ? "" : ", ").append(":kind").append(i);
        params.add(Map.entry("kind" + i, filter.kinds().get(i)));
      }
      sql.append(')');
    }
    if (filter.statuses() != null && !filter.statuses().isEmpty()) {
      sql.append(" AND u.status IN (");
      for (int i = 0; i < filter.statuses().size(); i++) {
        sql.append(i == 0 ? "" : ", ").append(":status").append(i);
        params.add(Map.entry("status" + i, filter.statuses().get(i)));
      }
      sql.append(')');
    }
    if (filter.searchTerm() != null) {
      // Case-insensitive over the customer's reference (memo) or the other
      // leg's IBAN; the term's own LIKE wildcards are escaped so a literal
      // % or _ in a memo searches literally, not as a pattern.
      sql.append(" AND (u.memo ILIKE :q ESCAPE '\\' OR a.iban ILIKE :q ESCAPE '\\')");
      params.add(Map.entry("q", "%" + escapeLike(filter.searchTerm()) + "%"));
    }
  }

  /** Escapes \, % and _ so the term is matched literally. */
  static String escapeLike(String term) {
    return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }
}
