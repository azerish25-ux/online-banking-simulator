package com.bank.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Whatever an attacker puts in the Authorization header, the answer must be a
 * clean 401 from the security chain: never a 500. jjwt throws plain
 * IllegalArgumentException (not JwtException) for empty or structurally
 * broken tokens, which used to escape the auth filter entirely.
 */
@SpringBootTest(properties = "app.auth.rate-limit.per-minute=1000")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MalformedBearerTokenTest {

  @Autowired MockMvc mvc;

  @Test
  void malformedAuthorizationHeadersAnswer401Not500() throws Exception {
    for (String header : List.of("Bearer ", "Bearer", "Bearer not-a-jwt", "Bearer a.b.c")) {
      mvc.perform(get("/api/v1/accounts").header("Authorization", header))
          .andExpect(status().isUnauthorized());
    }
  }
}
