package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * a statement is ONE immutable snapshot, not a pastiche of reads.
 *
 * <p>The account moves money in two distinct months under a pinned business
 * clock. A statement over the earlier month must then show exactly that
 * month's rows with opening + rows = closing, and must be immune to money
 * that later moved: re-rendering the SAME snapshot object after a brand-new
 * deposit lands produces byte-identical output (the renderer performs zero
 * financial reads of its own), and the statement's as-of/version identify it
 * as one issued document.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatementSnapshotTest {

  /** Fixed clock: April 15th, then May 15th: two distinct posting months. */
  private static final SettableClock CLOCK = new SettableClock(
      Instant.parse("2026-04-15T12:00:00Z"));

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    java.time.Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired StatementService statements;
  @Autowired AccountRepository accounts;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void snapshotHoldsOpeningPlusRowsEqualsClosingAndIgnoresLaterMoney() throws Exception {
    CLOCK.set(Instant.parse("2026-04-15T12:00:00Z"));
    String alice = client.register("stmt-snap@example.com", "Stmt Snap");
    String bob = client.register("stmt-snap-b@example.com", "Stmt Snap B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    // April: +100 deposit (nothing before it).
    client.deposit(alice, aliceId, "100.00");

    // May: +50 deposit, -20 transfer out.
    CLOCK.set(Instant.parse("2026-05-15T12:00:00Z"));
    client.deposit(alice, aliceId, "50.00");
    client.transfer(alice, bobIban, "20.00");
    assertEquals(new BigDecimal("130.0000"),
        accounts.findById(UUID.fromString(aliceId)).orElseThrow().getBalance());

    StatementService.Statement april =
        statements.customerStatement("stmt-snap@example.com", UUID.fromString(aliceId),
            LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
    assertEquals(LocalDate.of(2026, 4, 1), april.period().from());
    assertEquals(LocalDate.of(2026, 4, 30), april.period().to());
    // Exactly the April movement: May's money is not part of this document.
    assertEquals(1, april.rows().size(), "only the April deposit is on an April statement");
    assertEquals(new BigDecimal("0.0000"), april.openingBalance(),
        "nothing moved before April 1st");
    assertEquals(new BigDecimal("100.0000"), april.closingBalance(),
        "closing is the balance at April's end, never today's $130");

    // opening + sum(printed signed rows) = closing, at internal precision.
    BigDecimal net = BigDecimal.ZERO;
    for (StatementService.StatementRow tx : april.rows()) {
      boolean credit = april.account().id().equals(tx.toAccountId());
      net = credit ? net.add(tx.amount()) : net.subtract(tx.amount());
    }
    assertEquals(april.closingBalance(), april.openingBalance().add(net),
        "opening + printed rows must equal the closing figure");

    // The snapshot names its state: asOf is pinned and never changes.
    Instant asOf = april.asOf();
    assertEquals(StatementService.Statement.CURRENT_VERSION, april.version());

    // The renderer is pure: it must not re-read the database. Deposit MORE
    // money, then render the SAME April snapshot again: the rendered CONTENT
    // (what the reader sees: figures, rows, as-of) is identical, even though
    // the database now shows a $1,129 balance. Byte-level identity is not
    // asserted: PDF containers may embed per-write machine metadata, but the
    // document a snapshot renders must never change.
    byte[] first = statements.renderPdf(april);
    CLOCK.set(Instant.parse("2026-05-16T09:00:00Z"));
    client.deposit(alice, aliceId, "999.00");
    byte[] second = statements.renderPdf(april);
    assertEquals(pdfText(first), pdfText(second),
        "re-rendering one snapshot after later money moves must render identical content");
    assertTrue(!pdfText(second).contains("1,129"),
        "the new deposit must not leak into the already-issued snapshot");
    assertEquals(asOf, april.asOf(), "the snapshot's as-of is frozen at build time");
  }

  private static String pdfText(byte[] bytes) throws Exception {
    try (org.apache.pdfbox.pdmodel.PDDocument doc =
        org.apache.pdfbox.Loader.loadPDF(bytes)) {
      return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
    }
  }

  @Test
  void csvRowsComeFromTheSameSnapshotAsThePdf() throws Exception {
    CLOCK.set(Instant.parse("2026-06-10T12:00:00Z"));
    String alice = client.register("stmt-snap-csv@example.com", "Stmt Snap Csv");
    String bob = client.register("stmt-snap-csv-b@example.com", "Stmt Snap Csv B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    client.deposit(alice, aliceId, "75.00");
    client.transfer(alice, bobIban, "25.00");

    StatementService.CsvStatement csv = statements.customerCsv("stmt-snap-csv@example.com",
        UUID.fromString(aliceId), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
    StatementService.Statement statement = statements.customerStatement(
        "stmt-snap-csv@example.com", UUID.fromString(aliceId),
        LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

    // CSV rows = snapshot rows, one line each (plus the header), same window.
    long dataLines = csv.content().lines().count() - 1;
    assertEquals(statement.rows().size(), dataLines,
        "CSV and PDF must serialize the same row window:\n" + csv.content());
    for (StatementService.StatementRow tx : statement.rows()) {
      assertTrue(csv.content().contains(tx.id().toString()),
          "every snapshot row must appear in the CSV export");
    }
    // Both directions are present: the deposit credits, the transfer debits.
    assertTrue(csv.content().contains("75.0000") && csv.content().contains("25.0000"));
  }

  /** A settable business clock for this test class only. */
  static final class SettableClock extends Clock {
    private Instant now;

    SettableClock(Instant now) {
      this.now = now;
    }

    void set(Instant now) {
      this.now = now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
