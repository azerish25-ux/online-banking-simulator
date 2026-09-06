package com.bank.platform.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void freezeBlocksTransfersUntilUnfrozen() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");
    String alice = register("adm-alice@example.com", "Adm Alice");
    register("adm-bob@example.com", "Adm Bob");
    String aliceId = accountId(alice);
    String bobIban = accountIban(login("adm-bob@example.com", "secret123"));

    // Admin sees the new users in search.
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + admin).param("q", "adm-"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2));

    // A customer is refused from admin APIs.
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + alice))
        .andExpect(status().isForbidden());

    // Fund Alice, then freeze: her transfers are rejected with 400.
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"100.00"}"""))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/admin/accounts/" + aliceId + "/freeze")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"10.00"}""".formatted(bobIban)))
        .andExpect(status().isBadRequest());

    // Unfreeze restores the flow, and the audit trail shows both actions.
    mvc.perform(post("/api/v1/admin/accounts/" + aliceId + "/unfreeze")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + admin)
            .param("action", "ACCOUNT_FROZEN"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].action").value("ACCOUNT_FROZEN"));
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
