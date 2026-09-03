package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
  void sixthLoginAttemptInAMinuteIsRejected() throws Exception {
    String body = """
        {"email":"nobody@example.com","password":"wrong"}""";
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/api/v1/auth/login")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Retry-After", "60"));
  }
}
