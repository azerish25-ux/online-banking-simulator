package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.auth.TotpService;
import com.bank.platform.common.Brand;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * regression: tokens are single-purpose. A login challenge (purpose=mfa,
 * MFA audience) must NEVER authenticate a protected request, and an access
 * token must NEVER complete an MFA challenge. Purpose-less tokens, wrong
 * purpose/audience/issuer, expired tokens, bad signatures, and unapproved
 * algorithms are all rejected: while a properly completed MFA session keeps
 * working.
 */
@SpringBootTest(properties = "app.auth.rate-limit.per-minute=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaTokenPurposeTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TotpService totpService;
  @Autowired JwtService jwtService;

  @Value("${app.jwt.secret}") String jwtSecret;

  private String totpChallenge;
  private String totpSecret;
  private String plainAccess;

  @BeforeEach
  void setUp() throws Exception {
    String totpEmail = unique("totp");
    totpSecret = enableTotp(register(totpEmail));
    totpChallenge = challengeAfterLogin(totpEmail);
    plainAccess = register(unique("plain"));
  }

  @Test
  void challengeTokenCannotReadProtectedEndpoints() throws Exception {
    // /api/v1/auth/me, account reads and money mutations for a customer fixture.
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + totpChallenge))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + totpChallenge))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + totpChallenge)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"DE00000000000000000000\",\"amount\":\"1.00\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void challengeTokenForAdminCannotReachAdminEndpoints() throws Exception {
    // A password-stage challenge minted for the administrator account must not
    // open any admin surface (customer and admin fixtures alike).
    String adminChallenge = jwtService.generateMfa("admin-test@bank.local");
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + adminChallenge))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + adminChallenge))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void accessTokenCannotCompleteMfaChallenge() throws Exception {
    mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mfaToken\":\"" + plainAccess + "\",\"code\":\"000000\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));
  }

  @Test
  void completedMfaSessionStillWorks() throws Exception {
    String access = verifyChallenge(totpChallenge, totpSecret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());
  }

  @Test
  void purposeLessTokensAreRejected() throws Exception {
    String purposeLess = craft(subject(), Brand.JWT_AUDIENCE, null, future(), jwtSecret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + purposeLess))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongPurposeIsRejected() throws Exception {
    String refreshPurpose = craft(subject(), Brand.JWT_AUDIENCE, "refresh", future(), jwtSecret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + refreshPurpose))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongAudienceIsRejected() throws Exception {
    String wrongAud = craft(subject(), "simulator-other-app", "access", future(), jwtSecret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + wrongAud))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongIssuerIsRejected() throws Exception {
    String wrongIssuer = Jwts.builder()
        .issuer("someone-else")
        .audience().add(Brand.JWT_AUDIENCE).and()
        .subject(subject())
        .claim("purpose", "access")
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(future()))
        .signWith(hmacKey(jwtSecret), Jwts.SIG.HS256)
        .compact();
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + wrongIssuer))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void expiredTokenIsRejected() throws Exception {
    String expired = craft(subject(), Brand.JWT_AUDIENCE, "access", past(), jwtSecret);
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + expired))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void badSignatureIsRejected() throws Exception {
    String badSig = craft(subject(), Brand.JWT_AUDIENCE, "access", future(),
        "a-completely-different-secret-key-0123456789abcdef-0123456789abcdef");
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + badSig))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unapprovedAlgorithmIsRejected() throws Exception {
    // Signed with HS384 over the SAME key material: jjwt would verify it, which
    // is exactly why the service pins the approved algorithm (RFC 8725 section 3.1).
    String hs384 = Jwts.builder()
        .issuer(Brand.JWT_ISSUER)
        .audience().add(Brand.JWT_AUDIENCE).and()
        .subject(subject())
        .claim("purpose", "access")
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(future()))
        .signWith(hmacKey(jwtSecret), Jwts.SIG.HS384)
        .compact();
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + hs384))
        .andExpect(status().isUnauthorized());
  }

  // --- helpers ---------------------------------------------------------------

  private String unique(String label) {
    return label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
  }

  private String register(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Purpose Test\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String enableTotp(String token) throws Exception {
    MvcResult setup = mvc.perform(post("/api/v1/auth/totp/setup")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    String secret = json.readValue(setup.getResponse().getContentAsString(), JsonNode.class)
        .get("secret").asText();
    mvc.perform(post("/api/v1/auth/totp/enable")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"" + totpService.currentCode(secret) + "\"}"))
        .andExpect(status().isOk());
    return secret;
  }

  private String challengeAfterLogin(String email) throws Exception {
    MvcResult challenge = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
        .andExpect(status().isAccepted())
        .andReturn();
    return json.readValue(challenge.getResponse().getContentAsString(), JsonNode.class)
        .get("mfaToken").asText();
  }

  private String verifyChallenge(String challenge, String secret) throws Exception {
    MvcResult verified = mvc.perform(post("/api/v1/auth/mfa/verify")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"mfaToken\":\"" + challenge + "\",\"code\":\"" + totpService.currentCode(secret) + "\"}"))
        .andExpect(status().isOk())
        .andReturn();
    return json.readValue(verified.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  /** One distinct subject per token so reused keys can never alias users. */
  private String subject() {
    return "subject-" + UUID.randomUUID() + "@example.com";
  }

  private Instant future() {
    return Instant.now().plusSeconds(600);
  }

  private Instant past() {
    return Instant.now().minusSeconds(60);
  }

  private SecretKey hmacKey(String secret) {
    return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
  }

  private String craft(String subject, String audience, String purpose, Instant expiry,
      String signingSecret) {
    var builder = Jwts.builder()
        .issuer(Brand.JWT_ISSUER)
        .audience().add(audience).and()
        .subject(subject)
        .issuedAt(Date.from(Instant.now()))
        .expiration(Date.from(expiry));
    if (purpose != null) {
      builder.claim("purpose", purpose);
    }
    return builder.signWith(hmacKey(signingSecret), Jwts.SIG.HS256).compact();
  }
}
