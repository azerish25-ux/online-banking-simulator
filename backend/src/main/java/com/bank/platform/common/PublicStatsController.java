package com.bank.platform.common;

import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.TransactionRepository;
import java.math.BigDecimal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated landing-page numbers. Cached for five minutes and
 * evicted on register/transfer so the hero never shows stale counts after
 * money moves. Volume counts transfers only; deposits are internal rail noise.
 */
@RestController
public class PublicStatsController {

  /** Typed contract for the one public endpoint (amounts stay strings). */
  public record PublicStats(long users, long transfers, String volume) {}

  private final UserRepository users;
  private final TransactionRepository transactions;

  public PublicStatsController(UserRepository users, TransactionRepository transactions) {
    this.users = users;
    this.transactions = transactions;
  }

  @GetMapping("/api/public/stats")
  @Cacheable("public-stats")
  public PublicStats stats() {
    return new PublicStats(
        users.count(),
        transactions.countByFromAccountIdNotNull(),
        transactions.sumTransferVolume().orElse(BigDecimal.ZERO).toPlainString());
  }
}
