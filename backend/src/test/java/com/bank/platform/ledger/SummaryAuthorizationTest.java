package com.bank.platform.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Regression proof that authorization lives OUTSIDE the summary cache. The
 * cached computation is deliberately free of caller identity, so an ownership
 * check placed inside the cacheable method (as it once was) would be skipped
 * on every cache hit: letting any authenticated user who knows a foreign
 * account UUID read that account's month-by-month flows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SummaryAuthorizationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void cachedSummaryNeverLeaksAcrossUsers() throws Exception {
    String alice = client.register("sum-a@example.com", "Sum Alice");
    String bob = client.register("sum-b@example.com", "Sum Bob");
    String aliceId = client.accountId(alice);

    client.deposit(alice, aliceId, "1000.00");

    // Alice's own read warms the shared cache for this account+window.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/summary")
            .header("Authorization", "Bearer " + alice)
            .param("months", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[1].inflow").value("1000.0000"));

    // Bob, who knows Alice's account UUID, must never see the cached figures: // even though the row is sitting in the cache, authorization runs first.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/summary")
            .header("Authorization", "Bearer " + bob)
            .param("months", "2"))
        .andExpect(status().isNotFound());

    // And the owner still reads the (now cached) summary fine.
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/summary")
            .header("Authorization", "Bearer " + alice)
            .param("months", "2"))
        .andExpect(status().isOk());
  }
}
