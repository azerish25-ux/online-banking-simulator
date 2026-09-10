package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Fault injection around a deposit (containment proof): the enclosing
 * transaction must leave NO partial financial effect when a writer after the
 * account mutation fails. Committed state is inspected through RAW SQL (a
 * fresh persistence context), never from the test's own cached entities, so
 * a flush-hidden rollback cannot masquerade as success.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DepositFaultContainmentTest {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @MockitoSpyBean LedgerEventsService events;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, new tools.jackson.databind.ObjectMapper());
    Mockito.reset(events);
  }

  /**
   * A deposit whose final side-effect writer fails AFTER the account row was
   * mutated must roll back everything: no transaction row, no balance
   * movement, no journal line.
   */
  @Test
  void failureAfterAccountMutationLeavesNoPartialState() throws Exception {
    String token = client.register("fault-dep@example.com", "Fault Deposit");
    String accountId = client.accountId(token);
    String iban = client.accountIban(token);
    BigDecimal before = balanceOf(iban);
    int linesBefore = linesFor(accountId);

    String key = "fault-" + UUID.randomUUID();

    // The fault: the events writer (audit + notification) throws after the
    // account mutation, the operation row, and the journal write succeeded
    // inside the same transaction.
    Mockito.doThrow(new IllegalStateException("event writer lost"))
        .when(events).depositPosted(Mockito.any(), Mockito.any(), Mockito.any());

    try {
      mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
              .header("Authorization", "Bearer " + token)
              .header("Idempotency-Key", key)
              .contentType(MediaType.APPLICATION_JSON)
              .content("{\"amount\":\"25.00\"}"))
          .andExpect(status().isInternalServerError());

      // Fresh-persistence-context assertions via raw SQL: the deposit did
      // not half-happen.
      assertEquals(0, jdbc.queryForObject(
          "select count(*) from transactions where idempotency_key = ?", Integer.class, key),
          "no operation row may survive the failed deposit");
      assertEquals(0, before.compareTo(balanceOf(iban)),
          "the account balance must be unchanged after the failed deposit");
      assertEquals(linesBefore, linesFor(accountId),
          "no journal line may exist for the aborted operation");
    } finally {
      Mockito.reset(events);
    }
  }

  private BigDecimal balanceOf(String iban) {
    return jdbc.queryForObject(
        "select balance from accounts where iban = ?", BigDecimal.class, iban);
  }

  private int linesFor(String accountId) {
    Integer n = jdbc.queryForObject(
        "select count(*) from journal_lines where account_id = ?", Integer.class, accountId);
    return n == null ? -1 : n;
  }
}
