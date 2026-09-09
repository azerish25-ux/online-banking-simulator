package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * forwarding headers are trusted ONLY when the direct socket peer is
 * inside the configured CIDR allowlist. Allowlisted peers get per-real-client
 * buckets (not one shared bucket for the proxy); every other peer is bucketed
 * by its socket address, so spoofed X-Forwarded-For can never mint a fresh
 * budget. The default (no allowlist) stays spoof-proof: see RateLimitTest.
 *
 * <p>Every test uses its own account email: the per-account login throttle
 * is deliberately NOT reset by a new client IP, so a shared fixture
 * email would exhaust the account budget across methods and mask what the IP
 * buckets are doing.
 */
@SpringBootTest(properties = {
    "app.auth.rate-limit.per-minute=5",
    "app.auth.rate-limit.trusted-proxies=127.0.0.1, 10.0.0.0/8"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTrustedProxyTest {

  private static final String TRUSTED_PEER = "127.0.0.1";

  @Autowired MockMvc mvc;

  @Test
  void trustedForwardingBucketsPerRealClient() throws Exception {
    String body = loginBody("client-a");
    // An allowlisted peer's forwarded clients each keep their own budget.
    for (int i = 0; i < 5; i++) {
      mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "5.6.7.8"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "5.6.7.8"))
        .andExpect(status().isTooManyRequests());

    // ...while a different forwarded client is not throttled by that budget.
    mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "1.2.3.4"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void degenerateForwardedHeadersCannotMintFreshBuckets() throws Exception {
    String body = loginBody("client-b");
    // Whitespace-padded value is the same client as the clean one.
    for (int i = 0; i < 5; i++) {
      mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "203.0.113.9 "))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "203.0.113.9"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void garbageAndOversizedHeadersCollapseToOneSocketBucket() throws Exception {
    String body = loginBody("client-c");
    // Five DIFFERENT oversized/garbage values must all map to the same socket
    // bucket: an attacker rotating garbage to mint fresh buckets gets nothing.
    String[] garbage = {
        "9.9.9.9" + "A".repeat(300),
        "1.1.1.1" + "B".repeat(300),
        "2.2.2.2" + "C".repeat(300),
        "not-an-ip",
        "  "
    };
    for (int i = 0; i < garbage.length; i++) {
      mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", garbage[i]))
          .andExpect(status().isUnauthorized());
    }
    // A sixth request (blank header, same socket) proves they shared the one
    // socket budget and none of the garbage opened its own.
    mvc.perform(login(TRUSTED_PEER, body))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void untrustedPeerCanNeverUseForwardedHeadersToResetABudget() throws Exception {
    String body = loginBody("client-d");
    // 172.16.0.1 is NOT allowlisted: bucketed by socket address regardless of
    // any forwarding header, so spoofing a fresh identity buys nothing.
    String[] spoofed = {"8.8.8.8", "9.9.9.9", "1.1.1.1", "2.2.2.2", "3.3.3.3"};
    for (int i = 0; i < spoofed.length; i++) {
      mvc.perform(login("172.16.0.1", body).header("X-Forwarded-For", spoofed[i]))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(login("172.16.0.1", body).header("X-Forwarded-For", "4.4.4.4"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void commaChainsAndIpv6AreParsedDefensively() throws Exception {
    String body = loginBody("client-e");
    // A comma chain buckets by the LEFTMOST (client) entry.
    for (int i = 0; i < 5; i++) {
      mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "2001:db8::1, 6.6.6.6"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(login(TRUSTED_PEER, body).header("X-Forwarded-For", "2001:db8::1"))
        .andExpect(status().isTooManyRequests());
  }

  private MockHttpServletRequestBuilder login(String remoteAddr, String body) {
    return post("/api/v1/auth/login")
        .with(request -> {
          request.setRemoteAddr(remoteAddr);
          return request;
        })
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private String loginBody(String label) {
    String email = label + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    return "{\"email\":\"" + email + "\",\"password\":\"wrong\"}";
  }
}
