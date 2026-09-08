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
import java.util.UUID;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// NOT @Transactional: challenge verification reserves attempts in their own
// REQUIRES_NEW transaction, which cannot see rows created inside an outer
// test transaction. Like ChallengePersistenceTest, this class keeps its
// writes committed and isolates itself with unique per-run emails.
class TotpFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired TotpService totpService;

  @Test
  void setupEnableChallengeDisable() throws Exception {
    String email = "totpflow-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    String token = register(email, "Totp User");

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
                {"email":"%s","password":"secret123"}""".formatted(email)))
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
    // Disabling an active factor requires the current password too.
    mvc.perform(post("/api/v1/auth/totp/disable")
            .header("Authorization", "Bearer " + access)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"password":"secret123","code":"%s"}""".formatted(totpService.currentCode(secret))))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"secret123"}""".formatted(email)))
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
