package com.bank.platform.ledger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The inverted-range rule Period owns must be the SAME answer on every read
 * surface - history, the statement exports, and the operator console filter -
 * because before Period owned it, each returned a silent empty 200 (the
 * caller cannot tell "no rows match" from "your range is impossible"). The
 * 400 arrives through the RFC-7807 handler; open and one-sided windows stay
 * valid reads on the same endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PeriodWindowApiTest {

  private static final ZoneOffset UTC = ZoneOffset.UTC;

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void invertedHistoryRangeIsA400WithDetailNotAnEmptyPage() throws Exception {
    String alice = client.register("period-inv@example.com", "Period Inv");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "10.00");

    mvc.perform(history(alice, aliceId, later(), earlier()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.detail").value(containsString("inverted")));
  }

  @Test
  void invertedStatementRangeIsA400OnCsvAndPdf() throws Exception {
    String alice = client.register("period-inv-stmt@example.com", "Period Inv Stmt");
    String aliceId = client.accountId(alice);

    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.csv")
            .header("Authorization", "Bearer " + alice)
            .param("from", later()).param("to", earlier()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("inverted")));
    mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice)
            .param("from", later()).param("to", earlier()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("inverted")));
  }

  @Test
  void invertedOperatorConsoleFilterIsA400Too() throws Exception {
    String admin = client.adminToken();

    mvc.perform(get("/api/v1/admin/transactions")
            .header("Authorization", "Bearer " + admin)
            .param("from", later()).param("to", earlier()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("inverted")));
    // The unfiltered console read still works.
    mvc.perform(get("/api/v1/admin/transactions").header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
  }

  @Test
  void openOneSidedAndSameDayWindowsStayValidReads() throws Exception {
    String alice = client.register("period-open@example.com", "Period Open");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "10.00");
    String today = LocalDate.now(UTC).toString();

    mvc.perform(history(alice, aliceId, null, null)).andExpect(status().isOk());
    mvc.perform(history(alice, aliceId, today, null)).andExpect(status().isOk());
    mvc.perform(history(alice, aliceId, null, today)).andExpect(status().isOk());
    mvc.perform(history(alice, aliceId, today, today)).andExpect(status().isOk());
  }

  private MockHttpServletRequestBuilder history(String token, String accountId, String from, String to) {
    MockHttpServletRequestBuilder req = get("/api/v1/transactions")
        .header("Authorization", "Bearer " + token)
        .param("accountId", accountId);
    if (from != null) {
      req.param("from", from);
    }
    if (to != null) {
      req.param("to", to);
    }
    return req;
  }

  /** Tomorrow and yesterday - an inverted pair whatever day the suite runs. */
  private static String later() {
    return LocalDate.now(UTC).plusDays(1).toString();
  }

  private static String earlier() {
    return LocalDate.now(UTC).minusDays(1).toString();
  }

}
