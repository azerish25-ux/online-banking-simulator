package com.bank.platform.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The refresh cookie must be scoped to the path the BROWSER actually requests.
 * The UI talks to the API only through the Next.js rewrite proxy (/backend/*),
 * so a cookie scoped to the backend's own /api/v1/auth route would never be
 * sent - every session would die at access-token expiry. This pins the Path
 * attribute so the proxy shape cannot silently regress again.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthCookieScopeTest {

  @Autowired MockMvc mvc;

  @Test
  void refreshCookieIsScopedToTheProxiedBackendPath() throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"cookie-scope@example.com\",\"password\":\"secret123\",\"fullName\":\"Cookie Scope\"}"))
        .andExpect(status().isCreated())
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            containsString("refresh_token=")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            containsString("Path=/backend/v1/auth")))
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            not(containsString("Path=/api"))))
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            containsString("HttpOnly")))
        .andReturn();
    jakarta.servlet.http.Cookie refresh = result.getResponse().getCookie("refresh_token");
    if (refresh == null || refresh.getValue() == null || refresh.getValue().isEmpty()) {
      throw new AssertionError("register must set a refresh cookie");
    }

    // Rotation re-issues the cookie with the same scope.
    mvc.perform(post("/api/v1/auth/refresh")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh.getValue())))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            containsString("Path=/backend/v1/auth")));

    // Logout clears it on the same path.
    mvc.perform(post("/api/v1/auth/logout")
            .cookie(new jakarta.servlet.http.Cookie("refresh_token", refresh.getValue())))
        .andExpect(status().isNoContent())
        .andExpect(header().string(HttpHeaders.SET_COOKIE,
            containsString("Path=/backend/v1/auth; Max-Age=0")));
  }
}
