package com.bank.platform.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MoneyFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AuditLogRepository auditLogs;
  @Autowired jakarta.persistence.EntityManagerFactory emf;
  @Autowired MoneyService money;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void depositTransferIdempotencyAndHistory() throws Exception {
    String aliceToken = register("alice@example.com", "Alice");
    String bobToken = register("bob@example.com", "Bob");

    String aliceIban = client.accountIban(aliceToken);
    String bobIban = client.accountIban(bobToken);
    String aliceId = accountId(aliceToken);

    // Fund Alice with $500.
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"500.00"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value("500.0000"));

    // Alice sends $120 to Bob with an idempotency key.
    MvcResult first = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"120.00","memo":"Rent"}""".formatted(bobIban)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.amount").value("120.0000"))
        .andExpect(jsonPath("$.toIban").value(bobIban))
        .andReturn();
    String txId = objectMapper.readValue(first.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();

    // Replay with the same key and the SAME intent (F06): same row, balances
    // untouched. A changed payload under a used key is a conflict - see
    // IdempotencyScopingTest.changedIntentUnderSameKeyIsAConflict.
    MvcResult replay = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"120.00","memo":"Rent"}""".formatted(bobIban)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(txId))
        .andExpect(jsonPath("$.amount").value("120.0000"))
        .andReturn();

    // Balances: Alice 380, Bob 120.
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + aliceToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("380.0000"));
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + bobToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].balance").value("120.0000"));

    // Overdraft is rejected with 422 and changes nothing.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"10000.00"}""".formatted(bobIban)))
        .andExpect(status().isUnprocessableEntity());

    // Self-transfer is rejected with 400.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"1.00"}""".formatted(aliceIban)))
        .andExpect(status().isBadRequest());

    // History shows the transfer on Alice's account.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + aliceToken)
            .param("accountId", aliceId)
            .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].toIban").value(bobIban));

    // Bob cannot peek at Alice's account: indistinguishable from a missing one.
    mvc.perform(get("/api/v1/accounts/" + aliceId).header("Authorization", "Bearer " + bobToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void missingAmountIsA400NotA500() throws Exception {
    String token = register("noamount@example.com", "No Amount");
    String accountId = accountId(token);
    String other = client.accountIban(register("noamount-b@example.com", "No Amount Bee"));

    // A body without `amount` must fail Bean Validation with a 400 - never
    // reach `new BigDecimal(null)` in the controller and 500.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","currency":"USD"}""".formatted(other)))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void amountsPastLedgerScaleRejectedCleanlyInsteadOf500() throws Exception {
    String email = "subcent@example.com";
    String token = register(email, "Subcent User");
    String accountId = accountId(token);
    String other = client.accountIban(register("subcent-b@example.com", "Subcent Bee"));

    // Sub-cent values at the ledger's own 4-decimal scale are fine.
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"0.0006"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value("0.0006"));
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"0.0004"}""".formatted(other)))
        .andExpect(status().isCreated());

    // Values finer than the ledger scale (5+ decimals) would round to zero and
    // violate the DB amount > 0 constraint. The API schema rejects them before
    // the service; the service itself must ALSO refuse them with a clean
    // validation error instead of surfacing a constraint violation as a 500.
    assertThrows(TransferValidationException.class, () -> money.deposit(
        email, java.util.UUID.fromString(accountId), new BigDecimal("0.00005"), null));
    assertThrows(TransferValidationException.class, () -> money.transfer(
        email, java.util.UUID.fromString(accountId), other,
        new BigDecimal("0.00005"), null, "sub-cent guard", null));
  }

  private String register(String email, String name) throws Exception {
    return client.register(email, name);
  }

  private String accountId(String token) throws Exception {
    return client.accountId(token);
  }

  @Test
  void transferAuditCarriesAmountAndIbans() throws Exception {
    String aliceToken = register("meta-alice@example.com", "Meta Alice");
    String bobToken = register("meta-bob@example.com", "Meta Bob");
    String aliceId = accountId(aliceToken);
    String bobIban = client.accountIban(bobToken);

    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"500.00"}"""))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"120.00"}""".formatted(bobIban)))
        .andExpect(status().isCreated());

    var posted = auditLogs.findAll().stream()
        .filter(a -> "TRANSFER_POSTED".equals(a.getAction()))
        .toList();
    assertTrue(posted.size() == 1);
    assertTrue(posted.get(0).getMetadata().contains("120.0000"));
    assertTrue(posted.get(0).getMetadata().contains(bobIban));
  }

  @Test
  void depositsAreCappedAndLargeOnesFlagged() throws Exception {
    String token = register("cap@example.com", "Cap User");
    String accountId = accountId(token);
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"100000.01"}"""))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"15000.00"}"""))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].flagged").value(true));
  }

  @Test
  void malformedCursorIsA400NotAnOverflow500() throws Exception {
    String token = register("hugepage@example.com", "Huge Page");
    String accountId = accountId(token);
    client.deposit(token, accountId, "10.00");
    // The old offset pager clamped page=Integer.MAX_VALUE so its int OFFSET
    // never overflowed into a SQL 500. Keyset paging has no depth to
    // overflow; the honest boundary is a malformed cursor, which is a 400.
    mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId)
            .param("cursor", "not-a-cursor")
            .param("size", "100"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void historyPagesNewestFirst() throws Exception {
    String token = register("pages@example.com", "Pages User");
    String accountId = accountId(token);
    for (int i = 1; i <= 25; i++) {
      mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
              .header("Authorization", "Bearer " + token)
              .header("Idempotency-Key", "dep-" + System.nanoTime())
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"amount":"1.00"}"""))
          .andExpect(status().isOk());
    }
    // Keyset walk: fetch every page via nextCursor until it goes null. The
    // union of identities across the three pages must be exactly the 25
    // deposits, each once, newest-first - an OFFSET pager would duplicate or
    // skip here the moment anything else touched the ledger mid-walk.
    java.util.List<String> seen = new java.util.ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      var builder = get("/api/v1/transactions")
          .header("Authorization", "Bearer " + token)
          .param("accountId", accountId)
          .param("size", "10");
      if (cursor != null) {
        builder.param("cursor", cursor);
      }
      MvcResult result = mvc.perform(builder)
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.total").value(25))
          .andReturn();
      JsonNode page = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
      JsonNode items = page.get("items");
      int expected = Math.min(10, 25 - seen.size());
      org.junit.jupiter.api.Assertions.assertEquals(expected, items.size(), "page " + pages + " size");
      for (JsonNode row : items) {
        org.junit.jupiter.api.Assertions.assertTrue(seen.add(row.get("id").asText()),
            "no duplicate across pages at row " + row.get("id").asText());
      }
      cursor = page.has("nextCursor") && !page.get("nextCursor").isNull()
          ? page.get("nextCursor").asText() : null;
      pages++;
    } while (cursor != null);
    org.junit.jupiter.api.Assertions.assertEquals(3, pages);
    org.junit.jupiter.api.Assertions.assertEquals(25, seen.size(), "exact union of identities");
  }

  @Test
  void historyIssuesBoundedQueries() throws Exception {
    String token = register("qc@example.com", "Qc User");
    String accountId = accountId(token);
    String other = client.accountIban(register("qc-b@example.com", "Qc Bee"));
    client.deposit(token, accountId, "100.00");
    for (int i = 0; i < 3; i++) {
      client.transfer(token, other, "10.00");
    }
    var stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    stats.clear();
    mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId))
        .andExpect(status().isOk());
    // Exactly five: auth lookup, ownership check, UNION page, count, batched IBAN map.
    org.junit.jupiter.api.Assertions.assertTrue(stats.getQueryExecutionCount() <= 5,
        "history must stay bounded, ran " + stats.getQueryExecutionCount());
  }
}
