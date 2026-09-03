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
class TotpFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TotpService totpService;

  @Test
  void setupEnableChallengeDisable() throws Exception {
    String token = register("totp@example.com", "Totp User");

    MvcResult setup = mvc.perform(post("/api/v1/auth/totp/setup")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").isNotEmpty())
        .andExpect(jsonPath("$.qrDataUri").value(org.hamcrest.Matchers.startsWith("data:image/png;base64,")))
        .andReturn();
    String secret = objectMapper.readValue(setup.getResponse().getContentAsString(), JsonNode.class)
        .get("secret").asText();

    // Enabling with a wrong code fails; a fresh code enables.
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code":"000000"}"""))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code":"%s"}""".formatted(totpService.currentCode(secret))))
        .andExpect(status().isOk());

    // Password login now returns a challenge instead of tokens.
    MvcResult challenge = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"totp@example.com","password":"secret123"}"""))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.mfaToken").isNotEmpty())
        .andReturn();
    String mfaToken = objectMapper.readValue(challenge.getResponse().getContentAsString(), JsonNode.class)
        .get("mfaToken").asText();

    mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"mfaToken":"%s","code":"000000"}""".formatted(mfaToken)))
        .andExpect(status().isUnauthorized());
    MvcResult verified = mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"mfaToken":"%s","code":"%s"}""".formatted(mfaToken, totpService.currentCode(secret))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andReturn();
    String access = objectMapper.readValue(verified.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();

    // The access token works, and disabling restores plain login.
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/totp/disable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"code":"%s"}""".formatted(totpService.currentCode(secret))))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"totp@example.com","password":"secret123"}"""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
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
}
