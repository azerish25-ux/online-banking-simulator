package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * enforcement: the journal writer is only reachable inside the transaction
 * that owns the financial operation. A posting must commit or roll back with
 * the operation's balance flip: never float on its own. This test deliberately
 * runs WITHOUT a test-managed transaction and proves the boundary rejects an
 * untransactioned write.
 */
@SpringBootTest
@ActiveProfiles("test")
class JournalBoundaryTest {

  @Autowired JournalService journalService;
  @Autowired JournalEntryRepository entries;
  @Autowired JournalLineRepository lines;

  @Test
  void journalPostingOutsideATransactionIsRejectedBeforeAnythingIsWritten() {
    long entriesBefore = entries.count();
    long linesBefore = lines.count();

    TransferValidationException rejected = assertThrows(TransferValidationException.class,
        () -> journalService.post(
            JournalKind.DEPOSIT, "no-tx-" + UUID.randomUUID(), Instant.now(), "outside a tx",
            JournalService.Posting.account(UUID.randomUUID(), new BigDecimal("10.0000")),
            JournalService.Posting.counter(JournalLine.SIMULATOR_FUNDING,
                new BigDecimal("10.0000").negate())));
    assertTrue(rejected.getMessage().contains("transaction"),
        "the rejection names the missing transaction boundary: " + rejected.getMessage());

    // Even a perfectly balanced pair is refused: the guard fires before the
    // write, so the journal gained nothing.
    assertTrue(entries.count() == entriesBefore, "no entry escaped without a transaction");
    assertTrue(lines.count() == linesBefore, "no line escaped without a transaction");
  }
}
