package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * F11 durable receipt lookup: GET /api/v1/transfers/{id} returns the caller's
 * own operation with its authoritative status and times, is bookmarkable, and
 * never discloses a foreign or unknown id (same 404 either way).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReceiptLookupTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, json);
  }

  @Test
  void ownPostedTransferIsFetchableWithStatusAndTimes() throws Exception {
    String alice = client.register("receipt-a@example.com", "Receipt A");
    String bob = client.register("receipt-b@example.com", "Receipt B");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "100.00");
    String txId = client.transferId(alice, client.accountIban(bob), "12.34", "receipt memo");

    MvcResult result = mvc.perform(get("/api/v1/transfers/" + txId)
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    assertEquals(txId, body.get("id").asText());
    // The wire keeps the ledger's 4-decimal scale; the receipt formats for display.
    assertEquals("12.3400", body.get("amount").asText());
    assertEquals("POSTED", body.get("status").asText());
    assertEquals("TRANSFER", body.get("kind").asText());
    assertEquals("receipt memo", body.get("memo").asText());
    assertTrue(!body.get("createdAt").asText().isEmpty(), "request time present");
    assertTrue(!body.get("postedAt").asText().isEmpty(), "posting time present");
    assertNotNull(body.get("toIban"));
  }

  @Test
  void heldTransferKeepsPostingTimeNullUntilSettled() throws Exception {
    String alice = client.register("receipt-held-a@example.com", "Receipt Held A");
    String bob = client.register("receipt-held-b@example.com", "Receipt Held B");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "20000.00");
    // ≥ the review threshold (10,000) → HELD.
    String txId = client.transferId(alice, client.accountIban(bob), "15000.00", "big wire");

    MvcResult result = mvc.perform(get("/api/v1/transfers/" + txId)
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    assertEquals("HELD", body.get("status").asText());
    assertTrue(body.get("postedAt").isNull(), "HELD row has no posting time (F04)");
    assertTrue(!body.get("createdAt").asText().isEmpty());
  }

  @Test
  void foreignAndUnknownIdsAreTheSame404() throws Exception {
    String alice = client.register("receipt-x-a@example.com", "Receipt X A");
    String bob = client.register("receipt-x-b@example.com", "Receipt X B");
    String carol = client.register("receipt-x-c@example.com", "Receipt X C");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "50.00");
    String txId = client.transferId(alice, client.accountIban(bob), "5.00", "private");

    // Both LEGS may read the operation (Alice the sender, Bob the recipient -
    // the receipt is the same object for both), but an unrelated third party
    // must not: Carol gets the same 404 as an unknown id, disclosing nothing.
    mvc.perform(get("/api/v1/transfers/" + txId)
            .header("Authorization", "Bearer " + carol))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/transfers/00000000-0000-0000-0000-000000000000")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isNotFound());
  }
}
