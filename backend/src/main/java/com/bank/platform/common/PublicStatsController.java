package com.bank.platform.common;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.ledger.TransactionRepository;
import com.bank.platform.ledger.TxKind;
import com.bank.platform.ledger.TxStatus;
import java.math.BigDecimal;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated landing-page numbers. Cached for five minutes and
 * evicted on register/transfer so the hero never shows stale counts after
 * money moves.
 *
* <p>the transfer figure is a KIND + posted-status classification, never
 * a "has a from side" test. The interest engine posts loan charges that DO
 * carry a from side and deposits/credits that do not: counting rows by their
 * shape presented engine interest as user transfers. Only rows the rail
 * labelled TRANSFER that actually posted count; HELD/CANCELLED intents never
 * moved money and INTEREST/DEPOSIT rows are engine rail, not user transfers.
 */
@RestController
public class PublicStatsController {

  /** Typed contract for the one public endpoint (amounts stay strings). */
  public record PublicStats(long users, long accounts, long transfers, String volume) {}

  private final UserRepository users;
  private final AccountRepository accounts;
  private final TransactionRepository transactions;

  public PublicStatsController(
      UserRepository users, AccountRepository accounts, TransactionRepository transactions) {
    this.users = users;
    this.accounts = accounts;
    this.transactions = transactions;
  }

  @GetMapping("/api/public/stats")
  @Cacheable("public-stats")
  public PublicStats stats() {
    // POSTED user transfers by kind: engine postings (DEPOSIT/INTEREST) and
    // instructions that never moved money (HELD/CANCELLED) stay out.
    return new PublicStats(
        users.count(),
        accounts.count(),
        transactions.countByKindAndStatus(TxKind.TRANSFER, TxStatus.POSTED),
        transactions.sumAmountByKindAndStatus(TxKind.TRANSFER, TxStatus.POSTED)
            .orElse(BigDecimal.ZERO).toPlainString());
  }
}
