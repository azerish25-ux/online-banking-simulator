package com.bank.platform.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Shared API test client. Owns the register/deposit/transfer choreography so
 * the flow tests assert semantics instead of repeating MockMvc plumbing.
 */
public final class ApiTestClient {

  private final MockMvc mvc;
  private final ObjectMapper json;

  public ApiTestClient(MockMvc mvc, ObjectMapper json) {
    this.mvc = mvc;
    this.json = json;
  }

  /** Operator token from the test-profile seed (admin-test@bank.local). */
  public String adminToken() throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"admin-test@bank.local\",\"password\":\"admin-test-123\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  public String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"%s\"}".formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  public String accountId(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("id").asText();
  }

  public String accountIban(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get(0).get("iban").asText();
  }

  public void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"amount\":\"%s\"}".formatted(amount)))
        .andExpect(status().isOk());
  }

  public String transfer(String token, String toIban, String amount) throws Exception {
    return transferWithKey(token, toIban, amount, null);
  }

  public String transferWithKey(String token, String toIban, String amount, String idempotencyKey) throws Exception {
    var request = post("/api/v1/transfers")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"toIban\":\"%s\",\"amount\":\"%s\"}".formatted(toIban, amount));
    if (idempotencyKey != null) {
      request = request.header("Idempotency-Key", idempotencyKey);
    }
    MvcResult result = mvc.perform(request).andReturn();
    int status = result.getResponse().getStatus();
    if (status != 201 && status != 200) {
      throw new IllegalStateException("transfer failed: " + status
          + " " + result.getResponse().getContentAsString());
    }
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }
}
