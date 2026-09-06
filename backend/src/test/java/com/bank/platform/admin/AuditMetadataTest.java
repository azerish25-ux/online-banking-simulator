package com.bank.platform.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
 * The audit trail stores context (amounts, counterparties, IBANs) precisely so
 * an operator can reconstruct an event. The viewer endpoint must expose that
 * metadata, not just opaque action/entity rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuditMetadataTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void transferAuditsCarryAmountAndCounterparties() throws Exception {
    String alice = client.register("audit-a@example.com", "Audit Alice");
    String bob = client.register("audit-b@example.com", "Audit Bob");
    String aliceId = client.accountId(alice);
    String aliceIban = client.accountIban(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "5000.00");
    client.transfer(alice, bobIban, "250.00");

    String admin = login("admin-test@bank.local", "admin-test-123");
    mvc.perform(get("/api/v1/admin/audit-logs")
            .header("Authorization", "Bearer " + admin)
            .param("action", "TRANSFER_POSTED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].metadata.amount").value("250.0000"))
        .andExpect(jsonPath("$.content[0].metadata.from").value(aliceIban))
        .andExpect(jsonPath("$.content[0].metadata.to").value(bobIban));
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
