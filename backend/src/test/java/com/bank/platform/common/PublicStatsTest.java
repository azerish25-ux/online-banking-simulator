package com.bank.platform.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.ledger.TransactionRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public stats feed the landing hero. Counts are exact after fixtures and the
 * endpoint is reachable without authentication (it is the one public number).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PublicStatsTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactions;

  @Test
  void statsArePublicAndCountOnlyTransfers() throws Exception {
    String alice = register("stats-alice@example.com", "Stats Alice");
    String bob = register("stats-bob@example.com", "Stats Bob");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    long transfersBefore = transactions.countByKindAndStatus(
        com.bank.platform.ledger.TxKind.TRANSFER, com.bank.platform.ledger.TxStatus.POSTED);

    deposit(alice, aliceId, "500.00");
    transfer(alice, bobIban, "120.00");
    deposit(alice, aliceId, "40.00"); // deposits never count toward transfer volume

    mvc.perform(get("/api/public/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.users").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
        // Two registrations auto-open two checking accounts.
        .andExpect(jsonPath("$.accounts").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
        .andExpect(jsonPath("$.transfers").value(transfersBefore + 1))
        .andExpect(jsonPath("$.volume").value(org.hamcrest.Matchers.matchesPattern("\\d+(\\.\\d+)?")));
  }

  @Test
  void statsSurviveGarbageVolumesInAnEmptyLedger() throws Exception {
    // No fixtures: endpoint must answer with zeroed numbers, not 500.
    mvc.perform(get("/api/public/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transfers").value(org.hamcrest.Matchers.greaterThanOrEqualTo(0)));
  }

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"%s\"}".formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String accountId(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("id").asText();
  }

  private String accountIban(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("iban").asText();
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"%s\"}".formatted(amount)))
        .andExpect(status().isOk());
  }

  private void transfer(String token, String toIban, String amount) throws Exception {
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"%s\"}".formatted(toIban, amount)))
        .andExpect(status().isCreated());
  }
}
