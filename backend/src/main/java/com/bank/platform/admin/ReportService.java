package com.bank.platform.admin;

import com.bank.platform.ledger.Transaction;
import com.bank.platform.ledger.TransactionRepository;
import java.math.BigDecimal;
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

  public ReportService(TransactionRepository transactions) {
    this.transactions = transactions;
  }

  public record DayTotal(
      String date,
      long transfers, String transferVolume,
      long deposits, String depositVolume,
      long interestEvents, String interestNet) {}

  @Transactional(readOnly = true)
  public List<DayTotal> dailyTotals(int days) {
    int window = Math.min(Math.max(days, 1), 90);
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Instant since = today.minusDays(window - 1).atStartOfDay(ZoneOffset.UTC).toInstant();

    Map<LocalDate, Bucket> buckets = new LinkedHashMap<>();
    for (int i = window - 1; i >= 0; i--) {
      buckets.put(today.minusDays(i), new Bucket());
    }
    for (Transaction tx : transactions.findSince(since)) {
      LocalDate day = tx.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
      Bucket bucket = buckets.get(day);
      if (bucket == null) {
        continue;
      }
      String memo = tx.getMemo() == null ? "" : tx.getMemo();
      if (memo.startsWith("Simulated deposit")) {
        bucket.deposits++;
        bucket.depositVolume = bucket.depositVolume.add(tx.getAmount());
      } else if (memo.contains("interest")) {
        bucket.interestEvents++;
        // Savings pay out (to-only rows); loans charge (from-only rows).
        bucket.interestNet = tx.getFromAccountId() == null
            ? bucket.interestNet.add(tx.getAmount())
            : bucket.interestNet.subtract(tx.getAmount());
      } else if (tx.getFromAccountId() != null) {
        bucket.transfers++;
        bucket.transferVolume = bucket.transferVolume.add(tx.getAmount());
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
