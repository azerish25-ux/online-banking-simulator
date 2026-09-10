package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {

  /**
   * An account's journal postings inside a half-open window, oldest first:
   * the priced-period movements for the daily-balance walk. The upper bound
   * is exclusive, so a posting at the next period's first midnight is never
   * consumed by this period's day walk.
   */
  List<JournalLine> findByAccountIdAndPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAtAsc(
      UUID accountId, Instant from, Instant to);

  /** Signed total of an account's postings at or after an instant. */
  @Query("select coalesce(sum(l.amount), 0) from JournalLine l "
      + "where l.accountId = :accountId and l.postedAt >= :from")
  BigDecimal sumAmountByAccountIdAndPostedAtGreaterThanEqual(
      @Param("accountId") UUID accountId, @Param("from") Instant from);

  /**
   * Every customer account whose balance projection disagrees with what its
   * journal lines say it should be. Expected balances are DERIVED from the
   * journal (never compared with the balance table against itself), so a
   * balance that changed without a posting: or a posting whose balance was
   * never applied: shows up here. Empty result = projections reconcile.
   */
  @Query(value = "SELECT CAST(a.id AS VARCHAR) AS accountId, a.balance AS balance, "
      + "COALESCE(SUM(l.amount), 0) AS expected "
      + "FROM accounts a LEFT JOIN journal_lines l ON l.account_id = a.id "
      + "GROUP BY a.id, a.balance "
      + "HAVING a.balance <> COALESCE(SUM(l.amount), 0)",
      nativeQuery = true)
  List<AccountDifference> accountDifferences();

  interface AccountDifference {
    String getAccountId();
    BigDecimal getBalance();
    BigDecimal getExpected();
  }
}
