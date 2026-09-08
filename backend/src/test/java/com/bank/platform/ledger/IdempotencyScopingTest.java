package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
 * Idempotency keys are scoped to their originator: replaying your own key
 * returns the original row and moves money exactly once. Keys live in the
 * sender's own namespace (DB unique on from-account + key), so one user's key
 * string can never surface another user's transaction - reusing a foreign key
 * is simply a fresh transfer in the caller's own namespace - while reusing
 * your own key for a *different* destination is rejected outright.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdempotencyScopingTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired TransactionRepository transactions;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void ownerReplayReturnsOriginalRowAndDebitsOnce() throws Exception {
    String alice = client.register("idem-a@example.com", "Idem Alice");
    String bob = client.register("idem-b@example.com", "Idem Bob");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");

    String key = "idem-owner-" + UUID.randomUUID();
    String firstId = client.transferWithKey(alice, bobIban, "40.00", key);

    // Identical replay: original row back, no second debit.
    String replayId = client.transferWithKey(alice, bobIban, "40.00", key);
    assertEquals(firstId, replayId, "replay must return the original transaction");
    assertEquals(new BigDecimal("460.0000"), balance(aliceId), "replays must not double-debit");
  }

  @Test
  void foreignKeyStringNeverLeaksOrBlocksAnUnrelatedTransfer() throws Exception {
    String alice = client.register("idem-c@example.com", "Idem C");
    String bob = client.register("idem-d@example.com", "Idem D");
    String aliceId = client.accountId(alice);
    String aliceIban = client.accountIban(alice);
    String bobId = client.accountId(bob);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");
    client.deposit(bob, bobId, "100.00");

    String key = "idem-foreign-" + UUID.randomUUID();
    client.transferWithKey(alice, bobIban, "60.00", key);

    // Bob uses the same key string for his OWN transfer: keys are namespaced
    // per source account, so this is a fresh transfer in Bob's namespace - it
    // must post once, and must never surface Alice's transaction.
    String bobTxId = client.transferWithKey(bob, aliceIban, "5.00", key);
    MvcResult result = mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + bob)
            .param("accountId", bobId)
            .param("size", "20"))
        .andExpect(status().isOk())
        .andReturn();
    String body = result.getResponse().getContentAsString();
    assertFalse(body.contains("idem-foreign-"), "the foreign key must not surface Alice's row");
    assertTrue(body.contains(bobTxId), "Bob's own transfer posts exactly once under the same key string");
    assertEquals(new BigDecimal("155.0000"), balance(bobId), "deposit 100 + incoming 60 - outgoing 5");
  }

  @Test
  void sameOwnerKeyReusedForAnotherDestinationIsRejected() throws Exception {
    String alice = client.register("idem-g@example.com", "Idem G");
    String bob = client.register("idem-h@example.com", "Idem H");
    String carol = client.register("idem-i@example.com", "Idem I");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    String carolIban = client.accountIban(carol);
    client.deposit(alice, aliceId, "500.00");

    String key = "idem-dest-" + UUID.randomUUID();
    client.transferWithKey(alice, bobIban, "40.00", key);

    // The same sender reusing the key for a different destination must never
    // replay silently or double-post: it is a conflicting use of one key (    // - one key names one intent, and a changed intent is a 409 conflict).
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"7.00\"}".formatted(carolIban)))
        .andExpect(status().isConflict());
    assertEquals(new BigDecimal("460.0000"), balance(aliceId), "money moved exactly once");
  }

  @Test
  void changedIntentUnderSameKeyIsAConflictAndMovesMoneyOnce() throws Exception {
    String alice = client.register("idem-e@example.com", "Idem E");
    String bob = client.register("idem-f@example.com", "Idem F");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");

    String key = "idem-payload-" + UUID.randomUUID();
    client.transferWithKey(alice, bobIban, "40.00", key);

    // the key identifies the logical intent, not just the destination.
    // A retry that changed the amount under the same key is a conflict (409)
    // - never a silent replay of the older row, never a second posting.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"99.00\"}".formatted(bobIban)))
        .andExpect(status().isConflict());
    assertEquals(new BigDecimal("460.0000"), balance(aliceId), "one key may debit only once");
  }

  @Test
  void keylessTransferIsRejected() throws Exception {
    String alice = client.register("idem-k@example.com", "Idem K");
    String bob = client.register("idem-l@example.com", "Idem L");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "100.00");

    // User-submitted money movements require an idempotency key.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"10.00\"}".formatted(bobIban)))
        .andExpect(status().isBadRequest());
    assertEquals(new BigDecimal("100.0000"), balance(aliceId), "nothing moved");
  }

  private BigDecimal balance(String accountId) throws Exception {
    return accounts.findById(UUID.fromString(accountId)).orElseThrow().getBalance();
  }
}
