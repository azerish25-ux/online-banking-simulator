package com.bank.platform.auth;

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

    // No credentials on a protected endpoint is 401 (RFC-7807) - the browser
    // silent-refresh path only fires on 401, so 403 here would kill sessions
    // at expiry instead of repairing them.
    mvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"))
        .andExpect(jsonPath("$.detail").exists());
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

    // A missing password must be a clean validation failure, never a 500 or a
    // leak of BCrypt internals (password is required, not merely size-capped).
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"nopass@example.com","fullName":"No Password"}"""))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Validation Failed"));
  }

  @Test
  void refreshRotatesAndLogoutRevokes() throws Exception {
    MvcResult reg = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"ref@example.com","password":"secret123","fullName":"Ref User"}"""))
        .andExpect(status().isCreated())
        .andReturn();
    String refresh1 = reg.getResponse().getCookie("refresh_token").getValue();

    MvcResult ref = mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andReturn();
    String refresh2 = ref.getResponse().getCookie("refresh_token").getValue();
    org.junit.jupiter.api.Assertions.assertNotEquals(refresh1, refresh2);

    mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh1)))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh2)))
        .andExpect(status().isUnauthorized());

    MvcResult login = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"ref@example.com","password":"secret123"}"""))
        .andExpect(status().isOk())
        .andReturn();
    String refresh3 = login.getResponse().getCookie("refresh_token").getValue();
    mvc.perform(post("/api/v1/auth/logout")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh3)))
        .andExpect(status().isNoContent());
    mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh3)))
        .andExpect(status().isUnauthorized());
  }
}
