package com.bank.platform.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The 72-byte BCrypt ceiling must be enforced on bytes. An emoji is 4 UTF-8
 * bytes, so 18 emojis (72 bytes) are the exact boundary and 19 (76 bytes) must
 * be rejected - a character-based @Size would let all of them through and two
 * distinct 19-emoji passwords would silently share one truncated hash.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordBytesTest {

  @Autowired MockMvc mvc;

  private static final String EMOJI = "\uD83D\uDE80"; // rocket, 4 UTF-8 bytes

  @Test
  void registerRejectsPasswordsPast72BytesWithACleanMessage() throws Exception {
    // 19 * 4 = 76 bytes > 72. Rejected before BCrypt ever sees it.
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(EMOJI.repeat(19))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("password")));
  }

  @Test
  void registerAcceptsExactly72Bytes() throws Exception {
    // 18 * 4 = 72 bytes: exactly the boundary, no truncation happens.
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(EMOJI.repeat(18))))
        .andExpect(status().isCreated());
  }

  @Test
  void loginRejectsOversizedPasswordsInsteadOfHashingThem() throws Exception {
    String email = "bytes-login@example.com";
    mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"Bytes Login\"}".formatted(email)))
        .andExpect(status().isCreated());

    mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, EMOJI.repeat(19))))
        .andExpect(status().isBadRequest());
  }

  private static String json(String password) {
    return "{\"email\":\"bytes@example.com\",\"password\":\"" + password + "\",\"fullName\":\"Bytes\"}";
  }
}
