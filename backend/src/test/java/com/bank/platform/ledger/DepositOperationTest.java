package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import java.util.UUID;
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

/**
 * Operation identity for funding: deposits require an idempotency key
 * scoped to the funded account, identical replays return the original result
 * (money moves exactly once), a changed amount under the same key is a 409
 * conflict, and an authenticated status lookup resolves the caller's own
 * operation by key without ever disclosing another user's.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepositOperationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void identicalReplayPostsOnceAndReturnsCurrentBalance() throws Exception {
    String alice = client.register("op-dep-a@example.com", "Op Deposit A");
    String aliceId = client.accountId(alice);
    String key = "dep-replay-" + UUID.randomUUID();

    String first = depositBalance(alice, aliceId, "500.00", key);
    assertEquals("500.0000", first);

    // An identical replay returns the account's current state: the original
    // deposit already moved the money, and nothing may move twice for a key.
    String replay = depositBalance(alice, aliceId, "500.00", key);
    assertEquals("500.0000", replay, "replay must not double-credit");
  }

  @Test
  void depositResponseCarriesARecoverableOperationIdentity() throws Exception {
    String alice = client.register("op-dep-identity@example.com", "Op Deposit Identity");
    String aliceId = client.accountId(alice);
    String key = "dep-identity-" + UUID.randomUUID();

    // The response names the operation (id + key + authoritative status), not
    // only the updated balance, so a receipt and a status recovery exist even
    // when the client loses the page.
    MvcResult created = mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"250.00\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
    String operationId = body.get("operationId").asText();
    assertEquals(key, body.get("idempotencyKey").asText());
    assertEquals("POSTED", body.get("status").asText());
    assertEquals("250.0000", body.get("account").get("balance").asText());

    // The operation identity resolves through the authorized receipt lookup.
    mvc.perform(get("/api/v1/transfers/" + operationId)
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("DEPOSIT"))
        .andExpect(jsonPath("$.amount").value("250.0000"));

    // An identical replay returns the SAME operation identity: the row the
    // money actually posted under, never a new one.
    MvcResult replay = mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"250.00\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode replayBody = objectMapper.readTree(replay.getResponse().getContentAsString());
    assertEquals(operationId, replayBody.get("operationId").asText(),
        "a replay resolves to the original operation, never a fresh identity");
  }

  @Test
  void changedAmountUnderSameKeyIsAConflict() throws Exception {
    String alice = client.register("op-dep-b@example.com", "Op Deposit B");
    String aliceId = client.accountId(alice);
    String key = "dep-conflict-" + UUID.randomUUID();

    depositBalance(alice, aliceId, "500.00", key);
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"700.00\"}"))
        .andExpect(status().isConflict());
  }

  @Test
  void keylessDepositIsRejected() throws Exception {
    String alice = client.register("op-dep-c@example.com", "Op Deposit C");
    String aliceId = client.accountId(alice);
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"50.00\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void statusLookupResolvesOwnOperationOnly() throws Exception {
    String alice = client.register("op-dep-d@example.com", "Op Deposit D");
    String bob = client.register("op-dep-e@example.com", "Op Deposit E");
    String aliceId = client.accountId(alice);
    String bobId = client.accountId(bob);
    String key = "op-lookup-" + UUID.randomUUID();

    // Alice deposits under her key; Bob uses the same string for his own
    // deposit: keys live in each account's namespace, so both post.
    depositBalance(alice, aliceId, "200.00", key);
    depositBalance(bob, bobId, "9.00", key);

    // Alice resolves her own operation by key: it is her deposit, and it
    // posted exactly once.
    JsonNode aliceOp = operation(alice, key);
    assertEquals("200.0000", aliceOp.get("amount").asText());
    assertEquals("DEPOSIT", aliceOp.get("kind").asText());
    assertEquals("POSTED", aliceOp.get("status").asText());

    // Bob's own key resolves to HIS deposit, never Alice's row.
    JsonNode bobOp = operation(bob, key);
    assertEquals("9.0000", bobOp.get("amount").asText());

    // A third party cannot look up Alice's operation: ownership is
    // originator-scoped, and a foreign key is indistinguishable from an
    // unknown one (404), so probing never confirms another user's rows.
    String carol = client.register("op-dep-f@example.com", "Op Deposit F");
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + carol)
            .param("key", key))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + alice)
            .param("key", "never-used-key"))
        .andExpect(status().isNotFound());
  }

  @Test
  void transferStatusLookupFollowsHeldLifecycle() throws Exception {
    String alice = client.register("op-dep-g@example.com", "Op Deposit G");
    String bob = client.register("op-dep-h@example.com", "Op Deposit H");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");
    String key = "op-held-" + UUID.randomUUID();

    // Over-threshold → HELD intent. The status lookup must show HELD, not a
    // misleading success, until an operator settles it.
    String heldId = client.transferWithKey(alice, bobIban, "12000.00", key);
    JsonNode held = operation(alice, key);
    assertEquals("HELD", held.get("status").asText());
    assertEquals(heldId, held.get("id").asText());

    // Approve it, then the same lookup shows the terminal POSTED state.
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/review")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    assertEquals("POSTED", operation(alice, key).get("status").asText());
  }

  private String depositBalance(String token, String accountId, String amount, String key)
      throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"%s\"}".formatted(amount)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString())
        .path("account").get("balance").asText();
  }

  private JsonNode operation(String token, String key) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + token)
            .param("key", key))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
