package com.bank.platform.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Statement exports are a favorite smuggling channel: an oversized window can
 * pull unbounded rows into memory, and a memo can carry spreadsheet formula
 * characters that execute when the CSV is opened in Excel. Both are guarded
 * here (the small max-rows override makes the cap cheap to prove).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.statement.max-rows=5")
@Transactional
class StatementSafetyTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void oversizedStatementWindowIsRejectedBeforeStreaming() throws Exception {
    String alice = client.register("stmt-cap@example.com", "Stmt Cap");
    String aliceId = client.accountId(alice);

    for (int i = 0; i < 6; i++) {
      client.deposit(alice, aliceId, "1.00");
    }

    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isBadRequest());
  }

  @Test
  void formulaMemoIsNeutralizedInTheCsvExport() throws Exception {
    String alice = client.register("stmt-formula@example.com", "Stmt Formula");
    String bob = client.register("stmt-formula-b@example.com", "Stmt Formula B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "100.00");

    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"5.00\",\"memo\":\"=2+2\"}".formatted(bobIban)))
        .andExpect(status().isCreated());

    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        // The cell is prefixed with a single quote so it cannot execute.
        .andExpect(content().string(containsString("\"'=2+2\"")));
  }
}
