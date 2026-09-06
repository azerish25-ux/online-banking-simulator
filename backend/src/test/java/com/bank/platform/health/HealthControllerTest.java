package com.bank.platform.health;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

  @Autowired MockMvc mvc;

  @Test
  void requestIdIsMintedWhenAbsent() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(header().exists("X-Request-Id"));
  }

  @Test
  void requestIdIsPropagatedWhenProvided() throws Exception {
    mvc.perform(get("/api/health").header("X-Request-Id", "demo-trace-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Request-Id", "demo-trace-1"));
  }

  @Test
  void corsPreflightAllowsTraceHeader() throws Exception {
    mvc.perform(options("/api/v1/auth/login")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "X-Request-Id"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Headers", org.hamcrest.Matchers.containsString("X-Request-Id")));
  }

  @Test
  void healthReturnsUp() throws Exception {
    mvc.perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
