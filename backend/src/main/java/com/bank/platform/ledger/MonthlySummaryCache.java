package com.bank.platform.ledger;

import com.bank.platform.ledger.TransferDtos.MonthSummary;
import java.math.BigDecimal;
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

  public MonthlySummaryCache(TransactionRepository transactions) {
    this.transactions = transactions;
  }

  /** Monthly inflow/outflow (oldest first, zero-filled). Cached; evicted on any money mutation. */
  @Cacheable(value = "summaries", key = "#accountId + ':' + #months")
  @Transactional(readOnly = true)
  public List<MonthSummary> byAccount(UUID accountId, int months) {
    int window = Math.min(Math.max(months, 1), 24);
    YearMonth current = YearMonth.now(ZoneOffset.UTC);
    Instant since = current.minusMonths(window - 1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    Map<YearMonth, BigDecimal[]> buckets = new LinkedHashMap<>();
    for (int i = window - 1; i >= 0; i--) {
      buckets.put(current.minusMonths(i), new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
    }
    // Settled rows only: a HELD transfer is an intent and a CANCELLED row was
    // declined - neither moved money, so neither may appear as a flow. Every
    // other money-movement read (statements, daily totals, public stats)
    // applies the same POSTED filter; the summary must not be the outlier.
    for (Transaction tx : transactions.findSettledByAccountSince(accountId, TxStatus.POSTED, since)) {
      YearMonth key = YearMonth.from(tx.getCreatedAt().atZone(ZoneOffset.UTC));
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
