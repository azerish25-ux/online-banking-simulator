package com.bank.platform.admin;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
class OpsReviewTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void largeTransferFlaggedReviewedAndReported() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");
    String alice = register("ops-a@example.com", "Ops Alice");
    String bob = register("ops-b@example.com", "Ops Bob");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    deposit(alice, aliceId, "20000.00");

    // A five-figure transfer is auto-flagged; a small one is not.
    String bigId = transfer(alice, bobIban, "15000.00", true);
    transfer(alice, bobIban, "10.00", false);

    // Review queue filters to the flagged, unreviewed item.
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + admin)
            .param("flagged", "true")
            .param("reviewed", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].id").value(bigId))
        .andExpect(jsonPath("$.content[0].flagged").value(true));

    // Customers cannot touch the review queue.
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + alice))
        .andExpect(status().isForbidden());

    // Reviewing clears the queue and audits the decision.
    mvc.perform(post("/api/v1/admin/transactions/" + bigId + "/review")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reviewed").value(true));
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + admin)
            .param("flagged", "true")
            .param("reviewed", "false"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].amount").value("20000.0000"));
    mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + admin)
            .param("action", "TRANSACTION_REVIEWED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].entityId").value(bigId));

    // Daily totals include today with real volume.
    mvc.perform(get("/api/v1/admin/reports/daily-totals").header("Authorization", "Bearer " + admin)
            .param("days", "7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(7))
        .andExpect(jsonPath("$[6].transfers").value(2));

    // PDF statement downloads as a real PDF.
    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "application/pdf"))
        .andReturn();
    byte[] bytes = pdf.getResponse().getContentAsByteArray();
    assertTrue(bytes.length > 500, "PDF should have content");
    assertTrue(bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F',
        "PDF must start with %PDF");

    // CSV honors date ranges: future window is header-only.
    MvcResult csv = mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice)
            .param("from", LocalDate.now().plusDays(30).toString()))
        .andExpect(status().isOk())
        .andReturn();
    String csvBody = csv.getResponse().getContentAsString();
    assertTrue(csvBody.startsWith("id,created_at"), "CSV keeps its header");
    assertTrue(csvBody.trim().split("\n").length == 1, "future window has no rows");
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

  private String login(String email, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}""".formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String transfer(String token, String toIban, String amount, boolean flagged) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"%s"}""".formatted(toIban, amount)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.flagged").value(flagged))
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"%s"}""".formatted(amount)))
        .andExpect(status().isOk());
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
}
