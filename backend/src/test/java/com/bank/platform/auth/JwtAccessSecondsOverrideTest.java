package com.bank.platform.auth;

import static org.hamcrest.Matchers.not;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Server-side half of the browser refresh-expiry proof (the other half is
 * frontend/e2e/session.spec.ts in CI): with `app.jwt.access-seconds` set, the
 * login response reports the short TTL, that access token is rejected once it
 * expires, and the refresh cookie mints a successor that works. The browser
 * suite needs this override to prove silent refresh without waiting 15
 * minutes; this pins the server behavior the browser depends on.
 */
@SpringBootTest(properties = "app.jwt.access-seconds=3")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAccessSecondsOverrideTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void shortAccessTokensExpireAndRefreshRecovers() throws Exception {
    MvcResult register = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"short-ttl@example.com\",\"password\":\"secret123\",\"fullName\":\"Short TTL\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.expiresInSeconds").value(3))
        .andReturn();

    JsonNode body = objectMapper.readTree(register.getResponse().getContentAsString());
    String firstToken = body.get("accessToken").asText();

    // Fresh token is accepted.
    mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
        .andExpect(status().isOk());

    // Idle past the TTL: the same token is now rejected as expired - and the
    // rejection is a 401 RFC-7807 body (the status the browser keys its
    // silent refresh on), never the default 403 that would end the session.
    Thread.sleep(3_500);
    mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + firstToken))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.title").value("Unauthorized"));

    // The refresh cookie (still the browser's one credential) mints a
    // successor token that works again - the server chain the e2e exercises.
    jakarta.servlet.http.Cookie refresh = register.getResponse().getCookie("refresh_token");
    if (refresh == null || refresh.getValue() == null || refresh.getValue().isEmpty()) {
      throw new AssertionError("register must set a refresh cookie");
    }
    MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh.getValue())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.expiresInSeconds").value(3))
        .andReturn();
    String secondToken = objectMapper.readTree(rotated.getResponse().getContentAsString())
        .get("accessToken").asText();
    if (secondToken.equals(firstToken)) {
      throw new AssertionError("refresh must mint a new access token");
    }
    mvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + secondToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(not("")));
  }
}
