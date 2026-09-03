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
}