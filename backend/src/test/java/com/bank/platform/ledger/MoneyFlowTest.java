package com.bank.platform.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.bank.platform.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

  @Test
  void depositTransferIdempotencyAndHistory() throws Exception {
    String aliceToken = register("alice@example.com", "Alice");
    String bobToken = register("bob@example.com", "Bob");

    String aliceIban = accountIban(aliceToken);
    String bobIban = accountIban(bobToken);
    String aliceId = accountId(aliceToken);

    // Fund Alice with $500.
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + aliceToken)
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

    // Replay with the same key: same row, balances untouched.
    MvcResult replay = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .header("Idempotency-Key", "key-123")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"999.00","memo":"Mutated replay"}""".formatted(bobIban)))
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
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"10000.00"}""".formatted(bobIban)))
        .andExpect(status().isUnprocessableEntity());

    // Self-transfer is rejected with 400.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"1.00"}""".formatted(aliceIban)))
        .andExpect(status().isBadRequest());

    // History shows the transfer on Alice's account.
    mvc.perform(get("/api/v1/transactions").header("Authorization", "Bearer " + aliceToken)
            .param("accountId", aliceId)
            .param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].toIban").value(bobIban));

    // Bob cannot peek at Alice's account.
    mvc.perform(get("/api/v1/accounts/" + aliceId).header("Authorization", "Bearer " + bobToken))
        .andExpect(status().isForbidden());
  }

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"secret123","fullName":"%s"}""".formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String accountIban(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("iban").asText();
  }

  private void deposit(String token, String accountId) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"100.00"}"""))
        .andExpect(status().isOk());
  }

  private void transfer(String token, String toIban) throws Exception {
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"10.00"}""".formatted(toIban)))
        .andExpect(status().isCreated());
  }

  private String accountId(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("id").asText();
  }

  @Test
  void transferAuditCarriesAmountAndIbans() throws Exception {
    String aliceToken = register("meta-alice@example.com", "Meta Alice");
    String bobToken = register("meta-bob@example.com", "Meta Bob");
    String aliceId = accountId(aliceToken);
    String bobIban = accountIban(bobToken);

    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + aliceToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"500.00"}"""))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + aliceToken)
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
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"100000.01"}"""))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"15000.00"}"""))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].flagged").value(true));
  }

  @Test
  void historyPagesNewestFirst() throws Exception {
    String token = register("pages@example.com", "Pages User");
    String accountId = accountId(token);
    for (int i = 1; i <= 25; i++) {
      mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
              .header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON)
              .content("""
                  {"amount":"1.00"}"""))
          .andExpect(status().isOk());
    }
    checkPage(token, accountId, 0, 10);
    checkPage(token, accountId, 1, 10);
    checkPage(token, accountId, 2, 5);
  }

  private void checkPage(String token, String accountId, int page, int size) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + token)
            .param("accountId", accountId)
            .param("page", String.valueOf(page))
            .param("size", String.valueOf(size)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(25))
        .andExpect(jsonPath("$.content.length()").value(size))
        .andReturn();
    JsonNode content = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("content");
    org.junit.jupiter.api.Assertions.assertEquals(size, content.size());
    String previous = "9999-99-99";
    for (JsonNode row : content) {
      String created = row.get("createdAt").asText();
      org.junit.jupiter.api.Assertions.assertTrue(created.compareTo(previous) <= 0, "page must be newest-first");
      previous = created;
    }
  }

  @Test
  void historyIssuesBoundedQueries() throws Exception {
    String token = register("qc@example.com", "Qc User");
    String accountId = accountId(token);
    String other = accountIban(register("qc-b@example.com", "Qc Bee"));
    deposit(token, accountId);
    for (int i = 0; i < 3; i++) {
      transfer(token, other);
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
