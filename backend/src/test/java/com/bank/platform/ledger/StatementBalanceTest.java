package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * A statement's opening/closing figures must be true for the WINDOW on the
 * page, not for the account's lifetime. The default window happens to end
 * "now", so a buggy implementation that prints the current balance as the
 * closing passes every default-window test - it only fails once money moves
 * outside a requested past period. Both failure shapes are pinned here, plus
 * the two cut-algebra edges: rows that never moved money (HELD/CANCELLED
 * intents inside the window) must not shift a figure, and negative (drawn
 * LOAN) balances must carry their sign through the balance-minus-cut math.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatementBalanceTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void pastWindowIgnoresMovementAfterThePeriod() throws Exception {
    String alice = client.register("stmt-balance@example.com", "Stmt Balance");
    String bob = client.register("stmt-balance-b@example.com", "Stmt Balance B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    // Today only: +100 deposit, -40 transfer out. Balance is now 60.
    client.deposit(alice, aliceId, "100.00");
    client.transfer(alice, bobIban, "40.00");

    // A window that ended yesterday contains no rows, and everything that
    // moved (today) sits AFTER the window - the closing must read $0.00, the
    // account's true balance at the end of yesterday, never $60.00.
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    String pdf = statementText(alice, aliceId, yesterday, yesterday);
    assertTrue(pdf.contains("Opening $0.00"), "opening must be the balance at the window start:\n" + pdf);
    assertTrue(pdf.contains("Closing $0.00"), "closing must be the balance at the window end:\n" + pdf);
  }

  @Test
  void currentWindowReportsOpeningAndClosingFromItsOwnNet() throws Exception {
    String alice = client.register("stmt-balance2@example.com", "Stmt Balance Two");
    String bob = client.register("stmt-balance2-b@example.com", "Stmt Balance Two B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    client.deposit(alice, aliceId, "100.00");
    client.transfer(alice, bobIban, "40.00");

    // A window covering today shows the movements themselves: opening $0.00
    // (nothing before today), closing $60.00 (the current balance).
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String pdf = statementText(alice, aliceId, today, today);
    assertTrue(pdf.contains("Opening $0.00"), "opening must back out the in-window net:\n" + pdf);
    assertTrue(pdf.contains("Closing $60.00"), "closing must be the true period-end balance:\n" + pdf);
  }

  /**
   * HELD and CANCELLED rows are intents - no money ever moved - so they must
   * not shift Opening/Closing and must not appear in the statement CSV at
   * all. Both failure shapes (an intent counted as movement, an intent
   * printed as a row) would silently inflate the figures by the held amount
   * if the POSTED filter regressed; this pins the rendered output, not the
   * filter's unit behavior.
   */
  @Test
  void heldAndCancelledIntentsDoNotMoveTheFigures() throws Exception {
    String alice = client.register("stmt-intent@example.com", "Stmt Intent");
    String bob = client.register("stmt-intent-b@example.com", "Stmt Intent B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    String admin = client.adminToken();

    client.deposit(alice, aliceId, "22000.00");
    // Two review-threshold transfers: the first is declined (CANCELLED), the
    // second stays HELD. Neither moves money - the balance stays $22,000.
    String cancelled = heldTransfer(alice, bobIban, "10000.00", "Cancelled intent");
    mvc.perform(post("/api/v1/admin/transactions/" + cancelled + "/decline")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    heldTransfer(alice, bobIban, "10000.00", "Held intent");
    assertEquals("22000.0000", accountBalance(alice, aliceId), "intents move nothing");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String pdf = statementText(alice, aliceId, today, today);
    assertTrue(pdf.contains("Opening $0.00"), "intents must not shift the opening:\n" + pdf);
    assertTrue(pdf.contains("Closing $22,000.00"), "intents must not shift the closing:\n" + pdf);

    // The CSV prints movements, not intents - and the real deposit is there.
    String csv = statementCsv(alice, aliceId, today, today);
    assertTrue(csv.contains("22000.0000"), "the deposit must appear:\n" + csv);
    assertFalse(csv.contains("Cancelled intent"), "a CANCELLED row must not print:\n" + csv);
    assertFalse(csv.contains("Held intent"), "a HELD row must not print:\n" + csv);
  }

  /**
   * A drawn LOAN has a negative balance; the figures are balance-minus-cut,
   * so both the opening back-out and the closing carry the sign. This pins
   * the algebra on negatives - the past/current window tests above only ever
   * exercise positive balances.
   */
  @Test
  void drawnLoanStatementFiguresCarryTheNegativeSign() throws Exception {
    String alice = client.register("stmt-loan@example.com", "Stmt Loan");
    String checkingIban = client.accountIban(alice);
    JsonNode loan = openLoan(alice);
    String loanId = loan.get("id").asText();

    // Draw the full $1,000 credit limit into checking - the LOAN floor.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"1000.00\",\"fromAccountId\":\"%s\"}"
                .formatted(checkingIban, loanId)))
        .andExpect(status().isCreated());
    assertEquals("-1000.0000", accountBalance(alice, loanId), "loan draws to its floor");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String pdf = statementText(alice, loanId, today, today);
    assertTrue(pdf.contains("Opening $0.00"), "nothing before the draw:\n" + pdf);
    assertTrue(pdf.contains("Closing -$1,000.00"), "the negative debt must keep its sign:\n" + pdf);
  }

  /**
   * Draw then repay across zero: the loan ends positive after a negative
   * middle state, and the rendered figures must reflect the final balance
   * through the same cut arithmetic (in-window net +$1,000 → closing $1,000).
   */
  @Test
  void repaidLoanStatementFiguresCrossBackThroughZero() throws Exception {
    String alice = client.register("stmt-loan-r@example.com", "Stmt Loan Repaid");
    String checkingId = client.accountId(alice);
    String checkingIban = client.accountIban(alice);
    JsonNode loan = openLoan(alice);
    String loanId = loan.get("id").asText();
    String loanIban = loan.get("iban").asText();

    // Fund checking, draw the limit, then repay more than the draw: the loan
    // crosses -$1,000 and lands at +$1,000 (checking back to zero).
    client.deposit(alice, checkingId, "1000.00");
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"1000.00\",\"fromAccountId\":\"%s\"}"
                .formatted(checkingIban, loanId)))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"2000.00\"}".formatted(loanIban)))
        .andExpect(status().isCreated());
    assertEquals("1000.0000", accountBalance(alice, loanId), "repayment exceeds the draw");

    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    String pdf = statementText(alice, loanId, today, today);
    assertTrue(pdf.contains("Opening $0.00"), "in-window net backs out the opening:\n" + pdf);
    assertTrue(pdf.contains("Closing $1,000.00"), "positive after the crossing:\n" + pdf);
  }

  /** Opens a LOAN (one per user) and returns its account payload. */
  private JsonNode openLoan(String token) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"LOAN\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
  }

  /** A review-threshold transfer that the API records as HELD. */
  private String heldTransfer(String token, String toIban, String amount, String memo) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"%s\",\"memo\":\"%s\"}"
                .formatted(toIban, amount, memo)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private String accountBalance(String token, String accountId) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode accounts = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    for (JsonNode account : accounts) {
      if (account.get("id").asText().equals(accountId)) {
        return account.get("balance").asText();
      }
    }
    throw new AssertionError("account " + accountId + " not found");
  }

  private String statementCsv(String token, String accountId, LocalDate from, LocalDate to) throws Exception {
    MvcResult csv = mvc.perform(get("/api/v1/accounts/" + accountId + "/statement.csv")
            .header("Authorization", "Bearer " + token)
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk())
        .andReturn();
    return csv.getResponse().getContentAsString();
  }

  private String statementText(String token, String accountId, LocalDate from, LocalDate to) throws Exception {
    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + accountId + "/statement.pdf")
            .header("Authorization", "Bearer " + token)
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk())
        .andReturn();
    byte[] bytes = pdf.getResponse().getContentAsByteArray();
    assertTrue(bytes.length > 500, "PDF should have content");
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
  }
}
