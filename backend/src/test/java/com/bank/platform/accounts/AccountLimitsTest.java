package com.bank.platform.accounts;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountLimitsTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void oneOpenLoanPerUser() throws Exception {
    String token = client.register("loan-cap@example.com", "Loan Cap");

    // The first loan opens (and carries the $1,000 credit line).
    mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"LOAN"}"""))
        .andExpect(status().isCreated());
    // A second LOAN is refused: a user must not mint unbounded credit.
    mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"LOAN"}"""))
        .andExpect(status().isBadRequest());
    // Non-loan account types are unaffected by the cap.
    mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"SAVINGS"}"""))
        .andExpect(status().isCreated());
  }
}
