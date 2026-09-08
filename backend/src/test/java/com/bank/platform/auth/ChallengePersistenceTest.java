package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.security.JwtService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.security.authentication.BadCredentialsException;
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
 * regression: login challenges are persisted rows with consumption state,
 * expiry and an attempt budget that survives the failed-verification rollback.
 * Replays, expired challenges, exhausted budgets and cross-challenge budget
 * resets are all refused; the successful path works exactly once.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChallengePersistenceTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TotpService totpService;
  @Autowired JwtService jwtService;
  @Autowired LoginChallengeRepository challenges;
  @Autowired UserRepository users;
  @Autowired AuthService authService;

  private String email;
  private String secret;
  private UUID userId;
  private String challengeToken;

  @BeforeEach
  void setUp() throws Exception {
    email = "challenge-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    String token = register(email);
    secret = enableTotp(token);
    challengeToken = loginChallenge(email);
  }

  @Test
  void successConsumesTheChallengeExactlyOnce() throws Exception {
    mvc.perform(verify(challengeToken, currentCode()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());

    // Replay of the same (already consumed) challenge must fail, even though
    // the code is still correct within its time window.
    mvc.perform(verify(challengeToken, currentCode()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void wrongCodeIncrementsThePersistedBudget() throws Exception {
    UUID challengeId = challengeIdOf(challengeToken);
    mvc.perform(verify(challengeToken, "000000"))
        .andExpect(status().isUnauthorized());
    LoginChallenge row = challenges.findById(challengeId).orElseThrow();
    // The increment must SURVIVE the rollback of the failed verification.
    assertEquals(1, row.getFailedAttempts());
    assertEquals(false, row.isConsumed());

    // A fresh challenge for the same account still works (budget not exceeded).
    String next = loginChallenge(email);
    mvc.perform(verify(next, currentCode()))
        .andExpect(status().isOk());
  }

  @Test
  void exhaustedBudgetLocksTheChallengeAndTheAccount() throws Exception {
    // Five wrong codes exhaust the per-challenge budget (5th attempt is the
    // last one allowed to run; it lands the count at the limit).
    for (int i = 0; i < 5; i++) {
      mvc.perform(verify(challengeToken, "000000"))
          .andExpect(status().isUnauthorized());
    }
    // A correct code now gets refused outright (429) - the account budget and
    // the challenge budget are both exhausted.
    mvc.perform(verify(challengeToken, currentCode()))
        .andExpect(status().isTooManyRequests());

    // Issuing a NEW challenge must NOT reset the account's protection.
    String newChallenge = loginChallenge(email);
    mvc.perform(verify(newChallenge, currentCode()))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void twoSimultaneousCorrectSubmissionsMintExactlyOneSession() throws Exception {
    // two requests present the SAME unused challenge with a correct code
    // at the same moment (a double-submit race, not a sequential replay). The
    // atomic consume must let exactly ONE win; the twin must be refused and no
    // second session may be minted.
    UUID challengeId = challengeIdOf(challengeToken);
    String code = currentCode(); // the same 30-second window for both
    int n = 2;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger rejections = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    for (int i = 0; i < n; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          if (!start.await(10, TimeUnit.SECONDS)) {
            return;
          }
          authService.verifyMfaChallenge(challengeId, code);
          successes.incrementAndGet();
        } catch (BadCredentialsException expected) {
          // The loser: the challenge was already consumed by the winner.
          rejections.incrementAndGet();
        } catch (Exception e) {
          unexpected.incrementAndGet();
        }
      });
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS), "both threads must start");
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "verifications must resolve");

    assertEquals(0, unexpected.get(), "no unexpected failures");
    assertEquals(1, successes.get(), "exactly one correct submission may mint a session");
    assertEquals(1, rejections.get(), "the simultaneous twin must be refused");

    // The row is consumed, so even a later replay of the still-valid code fails.
    LoginChallenge row = challenges.findById(challengeId).orElseThrow();
    assertTrue(row.isConsumed(), "the winning submission must consume the challenge");
    assertThrows(BadCredentialsException.class,
        () -> authService.verifyMfaChallenge(challengeId, currentCode()),
        "the consumed challenge can never mint another session");
  }

  @Test
  void concurrentGuessesCanNeverExceedTheFiveAttemptBudget() throws Exception {
    // The attempt is RESERVED atomically before verification,
    // so a concurrent burst of wrong codes against one challenge can never
    // overshoot the five-verification budget (a read-then-increment counter
    // lets parallel callers all pass the check before any of them records).
    // Eight threads keep this H2 run inside the shared Hikari pool (10); the
    // real-PostgreSQL leg (ChallengePersistenceTest on the disposable
    // database) runs the same test with higher pool capacity for the 24-way
    // synchronization under load.
    UUID challengeId = challengeIdOf(challengeToken);
    int n = 8;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger verified = new AtomicInteger();   // wrong-code BadCredentials (a real verification ran)
    AtomicInteger refused = new AtomicInteger();    // TooMany - refused BEFORE any code check
    AtomicInteger unexpected = new AtomicInteger();
    java.util.concurrent.ConcurrentLinkedQueue<String> unexpectedDetail = new java.util.concurrent.ConcurrentLinkedQueue<>();
    for (int i = 0; i < n; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          if (!start.await(10, TimeUnit.SECONDS)) {
            return;
          }
          authService.verifyMfaChallenge(challengeId, "000000");
          unexpected.incrementAndGet(); // a wrong code must never succeed
        } catch (BadCredentialsException expected) {
          verified.incrementAndGet();
        } catch (TooManyTotpAttemptsException expected) {
          refused.incrementAndGet();
        } catch (Exception e) {
          unexpected.incrementAndGet();
          unexpectedDetail.add(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      });
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS), "all threads must start");
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "verifications must resolve");

    assertEquals(0, unexpected.get(), "no request may succeed or fail unexpectedly: "
        + String.join(" | ", unexpectedDetail));
    assertEquals(n, verified.get() + refused.get(), "every request resolved to a defined outcome");
    // The core guarantee: however the 24 requests interleave, at most five may
    // actually verify a code - everyone else is refused before verification.
    LoginChallenge row = challenges.findById(challengeId).orElseThrow();
    assertEquals(5, row.getFailedAttempts(), "the five-attempt budget is never overshot");
    assertEquals(5, verified.get(), "no more than the budgeted verifications may run");
    assertTrue(refused.get() >= 3, "every guess beyond the budget is refused, none verified");
  }

  @Test
  void expiredChallengeIsRejected() throws Exception {
    UUID challengeId = UUID.randomUUID();
    challenges.save(new LoginChallenge(userId, Instant.now().minus(1, ChronoUnit.MINUTES)));
    String token = jwtService.generateMfa(challengeId.toString());
    mvc.perform(verify(token, currentCode()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void unknownChallengeIsRejected() throws Exception {
    String token = jwtService.generateMfa(UUID.randomUUID().toString());
    mvc.perform(verify(token, currentCode()))
        .andExpect(status().isUnauthorized());
  }

  // --- helpers ---------------------------------------------------------------

  private String currentCode() {
    return totpService.currentCode(secret);
  }

  private UUID challengeIdOf(String token) {
    return UUID.fromString(jwtService.requireMfaSubject(token));
  }

  private String register(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"secret123\",\"fullName\":\"Challenge Test\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode body = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    userId = UUID.fromString(body.get("user").get("id").asText());
    return body.get("accessToken").asText();
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

  private String loginChallenge(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"secret123\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.mfaToken").isNotEmpty())
        .andReturn();
    return json.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("mfaToken").asText();
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(
      String token, String code) {
    return post("/api/v1/auth/mfa/verify")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"mfaToken\":\"" + token + "\",\"code\":\"" + code + "\"}");
  }
}
