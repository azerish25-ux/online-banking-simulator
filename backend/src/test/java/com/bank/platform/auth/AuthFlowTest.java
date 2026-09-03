package com.bank.platform.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class AuthFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void registerLoginMe() throws Exception {
    String registerBody = """
        {"email":"ada@example.com","password":"secret123","fullName":"Ada Lovelace"}""";

    MvcResult register = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.user.email").value("ada@example.com"))
        .andReturn();

    // Duplicate registration is a conflict.
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(registerBody))
        .andExpect(status().isConflict());

    // Login with wrong password is unauthorized (same message as unknown email).
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"ada@example.com","password":"wrong-pass"}"""))
        .andExpect(status().isUnauthorized());

    MvcResult login = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"ada@example.com","password":"secret123"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andReturn();

    String token = objectMapper
        .readValue(login.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();

    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("ada@example.com"));

    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isForbidden());
  }

  @Test
  void malformedJsonIsRfc7807() throws Exception {
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").exists());
  }

  @Test
  void badUuidPathIsRfc7807() throws Exception {
    String admin = loginAsAdmin();
    mvc.perform(get("/api/v1/accounts/not-a-uuid").header("Authorization", "Bearer " + admin))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").exists())
        .andExpect(jsonPath("$.detail").exists());
  }

  private String loginAsAdmin() throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"admin-test@bank.local","password":"admin-test-123"}"""))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  @Test
  void registerValidationFails() throws Exception {
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"not-an-email","password":"short","fullName":""}"""))
        .andExpect(status().isBadRequest());
  }
}
