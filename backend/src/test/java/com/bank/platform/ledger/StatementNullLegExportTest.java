package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression (customer + operator statement surfaces): a statement whose rows
 * contain a deposit REVERSAL (NULL destination leg, no memo - V29) and an
 * engine INTEREST row (NULL funding leg) must export through BOTH the CSV and
 * the PDF endpoints without error. Before the fix the PDF description
 * fallback probed the immutable IBAN map with the null leg and threw NPE on
 * {@code get(null)}, turning the customer (and operator) statement.pdf into a
 * 500; the CSV path already handled null legs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatementNullLegExportTest {

  private static final SettableClock CLOCK = new SettableClock(
      Instant.parse("2026-06-15T10:00:00Z"));

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired InterestService interestService;
  @Autowired TransactionRepository transactions;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    CLOCK.set(Instant.parse("2026-06-15T10:00:00Z"));
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void statementHoldingAReversalAndInterestRowExportsCsvAndPdfOnBothSurfaces()
      throws Exception {
    String alice = client.register("null-leg@example.com", "Null Leg Alice");
    String savingsId = open(alice, "SAVINGS");
    UUID savingsUuid = UUID.fromString(savingsId);

    // Fund on the 20th so June accrues real (nonzero) interest.
    CLOCK.set(Instant.parse("2026-06-20T10:00:00Z"));
    client.deposit(alice, savingsId, "1000.00");

    // July 1 prices June: the savings account earns interest, posting an
    // INTEREST row whose funding leg is NULL (settles against the engine).
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"),
        "the funded savings account must post a nonzero June interest row");

    // An operator then reverses the deposit: the REVERSAL row has a NULL
    // destination (back to the funding rail) and - unlike interest - no memo,
    // which is the exact shape that crashed the PDF description fallback.
    UUID depositId = transactions.findByAccountSince(savingsUuid, Instant.EPOCH).stream()
        .filter(tx -> tx.getKind() == TxKind.DEPOSIT)
        .map(Transaction::getId)
        .findFirst().orElseThrow();
    reverse(client.adminToken(), depositId, "Deposit keyed twice");

    List<Transaction> rows = transactions.findByAccountSince(savingsUuid, Instant.EPOCH);
    assertTrue(rows.stream().anyMatch(tx -> tx.getKind() == TxKind.INTEREST
        && tx.getToAccountId() != null && tx.getToAccountId().equals(savingsUuid)
        && tx.getFromAccountId() == null), "the INTEREST row is on the savings account");
    Transaction reversal = rows.stream()
        .filter(tx -> tx.getKind() == TxKind.REVERSAL)
        .findFirst().orElseThrow();
    assertEquals(savingsUuid, reversal.getFromAccountId());
    assertNull(reversal.getToAccountId(), "a deposit reversal returns to the funding rail");
    assertNull(reversal.getMemo(), "the reversal row is memo-less - the PDF fallback labels it");

    // Both exporter endpoints must return 200 over a window holding the
    // deposit, the INTEREST row and the REVERSAL row - and the PDF must carry
    // the graceful fallback label, not a 500.
    MvcResult csv = mvc.perform(get("/api/v1/accounts/" + savingsId + "/statement.csv")
            .header("Authorization", "Bearer " + alice)
            .param("from", "2026-06-01").param("to", "2026-07-01"))
        .andExpect(status().isOk())
        .andReturn();
    String csvBody = csv.getResponse().getContentAsString();
    assertEquals(4, csvBody.split("\\R").length, "header + deposit + interest + reversal:\n" + csvBody);

    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + savingsId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice)
            .param("from", "2026-06-01").param("to", "2026-07-01"))
        .andExpect(status().isOk())
        .andReturn();
    String text = compact(pdfText(pdf.getResponse().getContentAsByteArray()));
    assertTrue(text.contains("Transfertothefundingrail"),
        "the memo-less reversal must render its rail leg, not crash:\n" + text);
    assertTrue(text.contains("Transferfromthefundingrail")
        || text.contains("Simulateddeposit"), "the inbound deposit row is present:\n" + text);

    // The operator surface renders the same statement through the same
    // renderer - the admin PDF must return 200 too.
    mvc.perform(get("/api/v1/admin/accounts/" + savingsId + "/statement.pdf")
            .header("Authorization", "Bearer " + client.adminToken())
            .param("from", "2026-06-01").param("to", "2026-07-01"))
        .andExpect(status().isOk());
  }

  private String open(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"%s\"}".formatted(type)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private void reverse(String admin, UUID transactionId, String reason) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/admin/transactions/" + transactionId + "/reverse")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"%s\"}".formatted(reason)))
        .andReturn();
    assertTrue(result.getResponse().getStatus() == 200,
        "reverse failed: " + result.getResponse().getStatus()
            + " " + result.getResponse().getContentAsString());
  }

  private static String pdfText(byte[] bytes) throws Exception {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static final class SettableClock extends Clock {
    private Instant instant;

    SettableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant value) {
      instant = value;
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
      return instant;
    }
  }
}
