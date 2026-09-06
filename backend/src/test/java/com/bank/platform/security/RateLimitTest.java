package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.auth.rate-limit.per-minute=5")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitTest {

  @Autowired MockMvc mvc;



  @Test
  void authBudgetIsSharedAndSpoofProof() throws Exception {
    String body = """
        {"email":"nobody@example.com","password":"wrong"}""";
    // Four plain attempts burn most of the per-minute budget.
    for (int i = 0; i < 4; i++) {
      mvc.perform(post("/api/v1/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isUnauthorized());
    }
    // A spoofed header does not open a fresh bucket (proxy headers untrusted).
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "1.2.3.4")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isUnauthorized());
    // A differently-spoofed header still shares the one exhausted budget.
    mvc.perform(post("/api/v1/auth/login")
            .header("X-Forwarded-For", "9.9.9.9")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
  }
}
