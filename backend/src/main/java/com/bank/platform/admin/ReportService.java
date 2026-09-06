package com.bank.platform.admin;

import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.ledger.TxKind;
import com.bank.platform.ledger.TxStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

  private final TransactionRepository transactions;
  // The injected business clock (F04): the report's "today" is deterministic
  // and testable at day boundaries instead of a wall-clock LocalDate.now().
  private final Clock clock;

  public ReportService(TransactionRepository transactions, Clock clock) {
    this.transactions = transactions;
    this.clock = clock;
  }

  public record DayTotal(
      String date,
      long transfers, String transferVolume,
      long deposits, String depositVolume,
      long interestEvents, String interestNet) {}

  @Transactional(readOnly = true)
  public List<DayTotal> dailyTotals(int days) {
    int window = Math.min(Math.max(days, 1), 90);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    Instant since = today.minusDays(window - 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    Map<LocalDate, Bucket> buckets = new LinkedHashMap<>();
    for (int i = window - 1; i >= 0; i--) {
      buckets.put(today.minusDays(i), new Bucket());
    }
    // The status + window filters already ran in SQL; only settled rows can
    // arrive, so the loop just buckets them. (The `bucket == null` guard stays:
    // a row whose UTC day falls outside the requested window - impossible with
    // the SQL bound, but cheap insurance - must not distort totals.)
    for (TransactionRepository.PostedRow row : transactions.findPostedSince(since, TxStatus.POSTED)) {
      LocalDate day = row.getPostedAt().atZone(ZoneOffset.UTC).toLocalDate();
      Bucket bucket = buckets.get(day);
      if (bucket == null) {
        continue;
      }
      if (row.getKind() == TxKind.DEPOSIT) {
        bucket.deposits++;
        bucket.depositVolume = bucket.depositVolume.add(row.getAmount());
      } else if (row.getKind() == TxKind.INTEREST) {
        bucket.interestEvents++;
        // Savings pay out (to-only rows); loans charge (from-only rows).
        bucket.interestNet = row.getFromAccountId() == null
            ? bucket.interestNet.add(row.getAmount())
            : bucket.interestNet.subtract(row.getAmount());
      } else if (row.getFromAccountId() != null) {
        bucket.transfers++;
        bucket.transferVolume = bucket.transferVolume.add(row.getAmount());
      }
    }
    List<DayTotal> result = new ArrayList<>();
    buckets.forEach((day, bucket) -> result.add(new DayTotal(
        day.toString(), bucket.transfers, bucket.transferVolume.toPlainString(),
        bucket.deposits, bucket.depositVolume.toPlainString(),
        bucket.interestEvents, bucket.interestNet.toPlainString())));
    return result;
  }

  private static final class Bucket {
    long transfers;
    BigDecimal transferVolume = BigDecimal.ZERO;
    long deposits;
    BigDecimal depositVolume = BigDecimal.ZERO;
    long interestEvents;
    BigDecimal interestNet = BigDecimal.ZERO;
  }
}
