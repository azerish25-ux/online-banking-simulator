package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * regression: enrolling is a separate PENDING state. Starting a setup can
 * never destroy an active factor; replacing or disabling one requires the
 * current password plus proof of the EXISTING authenticator, and promotion
 * revokes old credentials immediately (security-version bump + refresh burn).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TotpReplacementTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TotpService totpService;
  @Autowired TotpEnrollmentRepository enrollments;
  @Autowired UserRepository users;

  private String email;
  private String password = "secret123";
  private String secretA;
  private String secretB;
  private UUID userId;
  private String sessionToken; // sv = current, usable access token

  @BeforeEach
  void setUp() throws Exception {
    email = "replace-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    userId = register(email);
    secretA = enableInitial();
    sessionToken = freshSession(secretA);
  }

  @Test
  void normalSessionCannotReplaceAnActiveFactor() throws Exception {
    startSetup(); // pending secret B
    // Enable with ONLY the new code (no password / old factor): must fail.
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB) + "\"}"))
        .andExpect(status().isUnauthorized());
    // The old factor is untouched.
    assertLoginWorksWith(secretA);
    assertLoginFailsWith(secretB);
  }

  @Test
  void replacementRequiresCurrentPassword() throws Exception {
    startSetup();
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"wrong-pass\",\"currentCode\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isUnauthorized());
    assertLoginWorksWith(secretA);
  }

  @Test
  void replacementRequiresProofOfExistingFactor() throws Exception {
    startSetup();
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"" + password + "\",\"currentCode\":\"000000\"}"))
        .andExpect(status().isUnauthorized());
    assertLoginWorksWith(secretA);
  }

  @Test
  void abortedEnrollmentPreservesTheOriginalFactor() throws Exception {
    startSetup();
    mvc.perform(post("/api/v1/auth/totp/cancel")
            .header("Authorization", "Bearer " + sessionToken))
        .andExpect(status().isNoContent());
    // Pending row is gone; enabling with B's code has nothing to promote.
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"" + password + "\",\"currentCode\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isUnauthorized());
    assertLoginWorksWith(secretA);
  }

  @Test
  void expiredPendingEnrollmentCannotBePromoted() throws Exception {
    startSetup();
    // Force the newest pending row past its expiry.
    TotpEnrollment pending = enrollments.findByUserIdOrderByCreatedAtDesc(userId).stream()
        .filter(e -> !e.isConsumed())
        .findFirst().orElseThrow();
    enrollments.deleteById(pending.getId());
    enrollments.save(new TotpEnrollment(userId, pending.getPendingSecret(),
        pending.getPendingKeyVersion(), Instant.now().minus(1, ChronoUnit.MINUTES)));
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + sessionToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"" + password + "\",\"currentCode\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isUnauthorized());
    assertLoginWorksWith(secretA);
  }

  @Test
  void successfulReplacementWorksExactlyOnceAndKillsOldCredentials() throws Exception {
    String oldAccessToken = sessionToken;
    startSetup();
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + oldAccessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"" + password + "\",\"currentCode\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isOk());

    // Old factor no longer works; new factor does.
    assertLoginFailsWith(secretA);
    assertLoginWorksWith(secretB);

    // The token that PERFORMED the replacement is already stale (security
    // version bumped): protected endpoints answer 401 immediately.
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + oldAccessToken))
        .andExpect(status().isUnauthorized());

    // Enabling again has no pending enrollment to promote: exactly one success.
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + freshSession(secretB))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secretB)
                + "\",\"currentPassword\":\"" + password + "\",\"currentCode\":\"" + codeOf(secretB) + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void disableRequiresPasswordAndCurrentFactor() throws Exception {
    String fresh = freshSession(secretA);
    // Wrong password: rejected, factor stays.
    mvc.perform(post("/api/v1/auth/totp/disable")
            .header("Authorization", "Bearer " + fresh)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"wrong-pass\",\"code\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isUnauthorized());
    // Correct password + code: disabled, and plain login works again.
    mvc.perform(post("/api/v1/auth/totp/disable")
            .header("Authorization", "Bearer " + fresh)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"password\":\"" + password + "\",\"code\":\"" + codeOf(secretA) + "\"}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk());
  }

  // --- helpers ---------------------------------------------------------------

  private UUID register(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\",\"fullName\":\"Replace Test\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    return UUID.fromString(body.get("user").get("id").asText());
  }

  private String enableInitial() throws Exception {
    String token = registerAccessToken();
    MvcResult setup = mvc.perform(post("/api/v1/auth/totp/setup")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    String secret = json.readValue(setup.getResponse().getContentAsString(), JsonNode.class)
        .get("secret").asText();
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + codeOf(secret) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }

  private String startSetup() throws Exception {
    MvcResult setup = mvc.perform(post("/api/v1/auth/totp/setup")
            .header("Authorization", "Bearer " + sessionToken))
        .andExpect(status().isOk())
        .andReturn();
    secretB = json.readValue(setup.getResponse().getContentAsString(), JsonNode.class)
        .get("secret").asText();
    return secretB;
  }

  private String registerAccessToken() throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  /** Full password → MFA login, returning a session token at the CURRENT sv. */
  private String freshSession(String secret) throws Exception {
    MvcResult challenge = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    String mfaToken = json.readValue(challenge.getResponse().getContentAsString(), JsonNode.class)
        .get("mfaToken").asText();
    MvcResult verified = mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + codeOf(secret) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(verified.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private void assertLoginWorksWith(String secret) throws Exception {
    String token = freshSession(secret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totpEnabled").value(true));
  }

  private void assertLoginFailsWith(String secret) throws Exception {
    String mfaToken = mfaChallenge();
    mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mfaToken\":\"" + mfaToken + "\",\"code\":\"" + codeOf(secret) + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  private String mfaChallenge() throws Exception {
    MvcResult challenge = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return json.readValue(challenge.getResponse().getContentAsString(), JsonNode.class)
        .get("mfaToken").asText();
  }

  private String codeOf(String secret) {
    return totpService.currentCode(secret);
  }
}
