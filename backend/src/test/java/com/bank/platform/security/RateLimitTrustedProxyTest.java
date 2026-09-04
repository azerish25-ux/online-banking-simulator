package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Behind a trusted proxy (the compose topology, where every request arrives
 * through Next.js), the limiter must bucket by the forwarded client IP - not
 * by the proxy's single address, which would make the whole app share one
 * budget. The default remains untrusted/spoof-proof (see RateLimitTest).
 */
@SpringBootTest(properties = {
    "app.auth.rate-limit.per-minute=5",
    "app.trust-proxy-headers=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTrustedProxyTest {

  @Autowired MockMvc mvc;

  @Test
  void trustedForwardingBucketsPerRealClient() throws Exception {
    String body = """
        {"email":"nobody@example.com","password":"wrong"}""";
    // One client burns its own budget...
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/v1/auth/login")
              .header("X-Forwarded-For", "5.6.7.8")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "5.6.7.8")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests());

    // ...while a different client is not throttled by that client's budget.
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "1.2.3.4")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void degenerateForwardedHeadersCannotMintFreshBuckets() throws Exception {
    String body = """
        {"email":"nobody@example.com","password":"wrong"}""";
    // Whitespace-padded value is the same client as the clean one above.
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/v1/auth/login")
              .header("X-Forwarded-For", "203.0.113.9 ")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "203.0.113.9")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests());

    // A blank or oversized header is no header at all: it falls back to the
    // socket address instead of opening an attacker-chosen bucket.
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "   ")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
  }
}
