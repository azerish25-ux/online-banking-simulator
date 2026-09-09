package com.bank.platform.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin transactions listing must have a total order. Two rows can share a
 * created_at (rows persisted in one flush get identical timestamps) and the
 * UUID primary key is random, so neither key can break the tie: the listing
 * tie-breaks on the database insert sequence instead. This test pins that
 * contract: rows sharing a timestamp come back newest-inserted-first, every
 * run, so page boundaries can never duplicate or skip a row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class QueueOrderDeterminismTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;

  @Test
  void tiedTimestampsResolveNewestInsertedFirst() throws Exception {
    String admin = login();
    // Two accounts (a sender + a recipient) so the rows satisfy the
    // from != to check constraint.
    String alice = register("queue-a@example.com", "Queue Alice");
    String bob = register("queue-b@example.com", "Queue Bob");
    String aliceAcc = accountId(alice);
    String bobAcc = accountId(bob);

    Instant tied = Instant.parse("2026-09-01T00:00:00Z");
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    // Insert two rows with the SAME created_at; seq assigns them in insert
    // order, so `second` is the more recently inserted of the pair.
    insertTx(first, aliceAcc, bobAcc, "5.0000", tied);
    insertTx(second, aliceAcc, bobAcc, "6.0000", tied);

    MvcResult result = mvc.perform(get("/api/v1/admin/transactions")
            .header("Authorization", "Bearer " + admin)
            .param("accountId", aliceAcc))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
    assertEquals(2, content.size(), "only the two inserted rows belong to this account");
    // Tied on created_at → the later insert (higher seq) comes first.
    assertEquals(second.toString(), content.get(0).get("id").asText());
    assertEquals(first.toString(), content.get(1).get("id").asText());
  }

  private void insertTx(UUID id, String fromAcc, String toAcc, String amount, Instant createdAt) {
    jdbc.update("INSERT INTO transactions (id, from_account_id, to_account_id, amount, currency, "
            + "kind, status, flagged, reviewed, created_at, posted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id.toString(), fromAcc, toAcc, amount, "USD", "TRANSFER", "POSTED", false, false,
        Timestamp.from(createdAt), Timestamp.from(createdAt));
  }

  private String login() throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin-test@bank.local\",\"password\":\"admin-test-123\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
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

  private String accountId(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("id").asText();
  }
}
