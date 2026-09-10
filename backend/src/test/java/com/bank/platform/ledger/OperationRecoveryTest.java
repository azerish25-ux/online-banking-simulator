package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * Operation recovery semantics (namespace fix): the key namespace is the
 * originating account, so recovery must be unambiguous: an owner with the
 * same key string on two of their own accounts gets a typed 409 from a
 * key-only lookup (never an arbitrary row), the account-scoped lookup
 * resolves each operation exactly, a bounded recent-operations list makes
 * completed-but-unacknowledged work discoverable after a lost response or a
 * re-login, and legacy rows without a canonical intent hash are never
 * replayed on destination alone.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OperationRecoveryTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TransactionRepository transactions;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  private String openSecondAccount(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"" + type + "\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void keyOnlyLookupAcrossTwoOwnAccountsIsAmbiguousNotArbitrary() throws Exception {
    String alice = client.register("op-rec-a@example.com", "Op Recovery A");
    String checkingId = client.accountId(alice);
    String savingsId = openSecondAccount(alice, "SAVINGS");
    String key = "op-ambig-" + UUID.randomUUID();

    // The SAME key string legitimately names one deposit on each of Alice's
    // own accounts (the key namespace is per originating account).
    depositTo(alice, checkingId, "100.00", key);
    depositTo(alice, savingsId, "7.00", key);

    // A key-only lookup cannot pick one of them: it must answer 409 with the
    // ambiguity, never silently return whichever row sorts first.
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + alice)
            .param("key", key))
        .andExpect(status().isConflict());

    // The account-scoped lookup IS the uniqueness namespace: each resolves
    // exactly its own operation.
    assertEquals("100.0000", operationAmount(alice, key, checkingId, "DEPOSIT"), "checking deposit resolves");
    assertEquals("7.0000", operationAmount(alice, key, savingsId, "DEPOSIT"), "savings deposit resolves");
  }

  @Test
  void recentOperationsListExposesCompletedButUnacknowledgedWork() throws Exception {
    String alice = client.register("op-rec-b@example.com", "Op Recovery B");
    String bob = client.register("op-rec-c@example.com", "Op Recovery C");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "200.00");
    String transferKey = "op-recent-tx-" + UUID.randomUUID();
    client.transferWithKey(alice, bobIban, "30.00", transferKey);

    // Both keyed operations appear, newest first (transfer posted after the
    // deposit), each carrying the identity a lost client would need.
    JsonNode list = recent(alice, 25);
    assertEquals(2, list.get("items").size());
    assertEquals("TRANSFER", list.get("items").get(0).get("kind").asText());
    assertEquals(transferKey, list.get("items").get(0).get("idempotencyKey").asText());
    assertEquals("30.0000", list.get("items").get(0).get("amount").asText());
    assertEquals("DEPOSIT", list.get("items").get(1).get("kind").asText());

    // The bound is honoured.
    JsonNode one = recent(alice, 1);
    assertEquals(1, one.get("items").size());
    assertEquals("TRANSFER", one.get("items").get(0).get("kind").asText());

    // Bob's list never surfaces Alice's operations.
    JsonNode bobList = recent(bob, 25);
    assertEquals(0, bobList.get("items").size());
  }

  @Test
  void sameKeyOnOneAccountAcrossKindsIsDisambiguatedByTheKindDiscriminator()
      throws Exception {
    String alice = client.register("op-rec-f@example.com", "Op Recovery F");
    String bob = client.register("op-rec-g@example.com", "Op Recovery G");
    String checkingId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    String key = "op-kind-" + UUID.randomUUID();

    // One key, one account, two kinds: a deposit funded checking and a
    // transfer originated from checking. Both rows are legitimate.
    depositTo(alice, checkingId, "100.00", key);
    client.transferWithKey(alice, bobIban, "20.00", key);

    // Account scope alone still spans the two namespaces: without a kind the
    // lookup answers the controlled ambiguity, never an arbitrary row.
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + alice)
            .param("key", key)
            .param("accountId", checkingId))
        .andExpect(status().isConflict());

    // The kind discriminator restores a unique namespace.
    assertEquals("100.0000", operationAmount(alice, key, checkingId, "DEPOSIT"));
    assertEquals("20.0000", operationAmount(alice, key, checkingId, "TRANSFER"));

    // An unknown kind is a 400, not a silent empty.
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + alice)
            .param("key", key)
            .param("accountId", checkingId)
            .param("kind", "WITHDRAWAL"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void recentListCarriesTheOriginatingAccountAndForeignKeysResolveToNothing()
      throws Exception {
    String alice = client.register("op-rec-h@example.com", "Op Recovery H");
    String bob = client.register("op-rec-i@example.com", "Op Recovery I");
    String aliceId = client.accountId(alice);
    String bobId = client.accountId(bob);
    client.deposit(alice, aliceId, "55.00");

    // Every item names the account whose namespace the key lives in.
    JsonNode list = recent(alice, 25);
    assertEquals(1, list.get("items").size());
    assertEquals(aliceId, list.get("items").get(0).get("originatingAccountId").asText());

    // A key probe scoped to an account the caller does not own is empty
    // (surfaced as 404), never another user's row.
    String bobKey = "op-foreign-" + UUID.randomUUID();
    depositTo(bob, bobId, "11.00", bobKey);
    mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + alice)
            .param("key", bobKey)
            .param("accountId", bobId))
        .andExpect(status().isNotFound());
  }

  @Test
  void legacyRowWithoutIntentHashIsAConflictNotASilentReplay() throws Exception {
    String alice = client.register("op-rec-d@example.com", "Op Recovery D");
    String bob = client.register("op-rec-e@example.com", "Op Recovery E");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");

    String key = "op-legacy-" + UUID.randomUUID();
    String txId = client.transferWithKey(alice, bobIban, "40.00", key);

    // Simulate a pre-V20 row: the canonical intent hash column is null, so
    // the only retained evidence is the destination: which is not proof that
    // a replayed amount/memo is the original intent.
    Transaction row = transactions.findById(UUID.fromString(txId)).orElseThrow();
    row.setRequestHash(null);
    transactions.saveAndFlush(row);

    // The same key replayed (even with the identical destination) must not
    // silently replay on destination alone: a conflict points at review and
    // money never moves twice.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"" + bobIban + "\",\"amount\":\"40.00\"}"))
        .andExpect(status().isConflict());
  }

  private void depositTo(String token, String accountId, String amount, String key)
      throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"" + amount + "\"}"))
        .andExpect(status().isOk());
  }

  private String operationAmount(String token, String key, String accountId, String kind)
      throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/operations")
            .header("Authorization", "Bearer " + token)
            .param("key", key)
            .param("accountId", accountId)
            .param("kind", kind))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    return body.get("amount").asText();
  }

  private JsonNode recent(String token, int limit) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/operations/recent")
            .header("Authorization", "Bearer " + token)
            .param("limit", String.valueOf(limit)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
