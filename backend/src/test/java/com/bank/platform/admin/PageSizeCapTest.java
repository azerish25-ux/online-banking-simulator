package com.bank.platform.admin;

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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paginated listings must never honor an unbounded size parameter: a caller
 * asking for size=1000000 would otherwise force the server to assemble and
 * serialize a whole table in one response. Every listing caps at 100.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PageSizeCapTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @Test
  void adminListingsCapRequestedPageSizes() throws Exception {
    String admin = login("admin-test@bank.local", "admin-test-123");

    mvc.perform(get("/api/v1/admin/audit-logs")
            .header("Authorization", "Bearer " + admin)
            .param("size", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));

    mvc.perform(get("/api/v1/admin/users")
            .header("Authorization", "Bearer " + admin)
            .param("size", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));

    mvc.perform(get("/api/v1/admin/transactions")
            .header("Authorization", "Bearer " + admin)
            .param("size", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void customerNotificationListingCapsRequestedPageSizes() throws Exception {
    String token = register("cap-c@example.com", "Cap Customer");
    mvc.perform(get("/api/v1/notifications")
            .header("Authorization", "Bearer " + token)
            .param("size", "100000"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(100));
  }

  private String login(String email, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"%s\"}"
                .formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }
}
