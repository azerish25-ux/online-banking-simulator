package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * The monthly money-flow summary is a money-movement read: it must count only
 * rows that actually settled (POSTED). A transfer HELD for operator review has
 * not moved money, and a CANCELLED transfer never will - neither may appear as
 * an outflow on the sender's dashboard, or a declined wire would show forever
 * as money that left the account.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SummarySettlementTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void heldAndCancelledTransfersNeverShowAsFlowUntilTheySettle() throws Exception {
    String alice = client.register("sum-hold-a@example.com", "Hold Alice");
    String bob = client.register("sum-hold-b@example.com", "Hold Bob");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");

    // A five-figure transfer is HELD: recorded as an intent, no money moved.
    MvcResult held = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"12000.00\"}".formatted(bobIban)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andReturn();
    String heldId = objectMapper.readValue(held.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();

    // Sender's outflow stays zero while the transfer merely awaits review.
    assertEquals("0", outflow(alice, aliceId), "a HELD intent is not a flow");

    // Declined: the row is CANCELLED and must NEVER read as an outflow.
    String admin = login("admin-test@bank.local", "admin-test-123");
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/decline")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertEquals("0", outflow(alice, aliceId), "a declined transfer never moved money");

    // A settled transfer below the threshold does count, exactly once.
    client.transfer(alice, bobIban, "9000.00");
    assertEquals("9000.0000", outflow(alice, aliceId));
  }

  @Test
  void approvedHoldCountsOnceAfterItSettles() throws Exception {
    String alice = client.register("sum-hold-c@example.com", "Hold Carol");
    String bob = client.register("sum-hold-d@example.com", "Hold Dan");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");

    MvcResult held = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"12000.00\"}".formatted(bobIban)))
        .andExpect(status().isCreated())
        .andReturn();
    String heldId = objectMapper.readValue(held.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();

    assertEquals("0", outflow(alice, aliceId), "held: zero flow");

    String admin = login("admin-test@bank.local", "admin-test-123");
    mvc.perform(post("/api/v1/admin/transactions/" + heldId + "/review")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("POSTED"));

    // After approval the money moved exactly once, so it shows exactly once.
    assertEquals("12000.0000", outflow(alice, aliceId));
  }

  private String outflow(String token, String accountId) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts/" + accountId + "/summary")
            .header("Authorization", "Bearer " + token)
            .param("months", "2"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode months = objectMapper.readTree(result.getResponse().getContentAsString());
    assertEquals(2, months.size(), "two zero-filled months expected");
    return months.get(1).get("outflow").asText();
  }

  private String login(String email, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }
}
