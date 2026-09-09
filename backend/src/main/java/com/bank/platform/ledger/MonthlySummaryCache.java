package com.bank.platform.ledger;

import com.bank.platform.ledger.TransferDtos.MonthSummary;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pure monthly-summary computation, cached by account + window.
 *
 * Deliberately free of any caller identity or ownership logic: authorization
 * happens in {@link MoneyService#summary} before this cache is consulted, so a
 * cache hit can never bypass an ownership check. The cache region ("summaries")
 * is evicted by every money mutation, exactly as before.
 */
@Component
public class MonthlySummaryCache {

  private final TransactionRepository transactions;
  private final Clock clock;

  public MonthlySummaryCache(TransactionRepository transactions, Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
  }

  /**
   * Monthly inflow/outflow (oldest first, zero-filled) for the {@code window}
   * months ending at (and including) {@code asOf}.
   *
   * <p>The cache key names the account, the WINDOW SIZE and the
   * explicit AS-OF MONTH, so two requests that anchor in different months can
   * never share one cached list: advancing the business clock across a month
   * end with NO financial mutation must produce the new window, and a stale
   * "as of last month" value must not be served for the current month.
   */
  @Cacheable(value = "summaries",
      key = "#accountId.toString() + '|' + #months + '|' + #asOf")
  @Transactional(readOnly = true)
  public List<MonthSummary> byAccount(UUID accountId, int months, YearMonth asOf) {
    int window = Math.min(Math.max(months, 1), 24);
    // The window anchors on the passed as-of month (the caller derives it from
    // the injected business clock), not on a wall-clock call inside here.
    YearMonth current = asOf;
    Instant since = current.minusMonths(window - 1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Map<YearMonth, BigDecimal[]> buckets = new LinkedHashMap<>();
    for (int i = window - 1; i >= 0; i--) {
      buckets.put(current.minusMonths(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
    }
    // Settled rows only: a HELD transfer is an intent and a CANCELLED row was
    // declined: neither moved money, so neither may appear as a flow. Every
    // other money-movement read (statements, daily totals, public stats)
    // applies the same POSTED filter; the summary must not be the outlier.
    for (Transaction tx : transactions.findSettledByAccountSince(accountId, TxStatus.POSTED, since)) {
      // Bucket on the posting month: an approval that lands after a
      // month boundary belongs to the month the money moved, not the month it
      // was requested.
      YearMonth key = YearMonth.from(tx.getPostedAt().atZone(ZoneOffset.UTC));
      BigDecimal[] slot = buckets.get(key);
      if (slot == null) {
        continue;
      }
      if (accountId.equals(tx.getToAccountId())) {
        slot[0] = slot[0].add(tx.getAmount());
      }
      if (accountId.equals(tx.getFromAccountId())) {
        slot[1] = slot[1].add(tx.getAmount());
      }
    }
    return buckets.entrySet().stream()
        .map(e -> new MonthSummary(
            e.getKey().toString(), e.getValue()[0].toPlainString(), e.getValue()[1].toPlainString()))
        .toList();
  }
}
