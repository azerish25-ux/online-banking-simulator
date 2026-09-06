package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The journal's real-PostgreSQL guarantees (F15), run against a disposable
 * PostgreSQL database (never bankdb). H2 cannot express these, so they live
 * here:
 *
 * <ul>
 *   <li>journal_entries and journal_lines are APPEND-ONLY - even the
 *       application's own role cannot UPDATE or DELETE a posted entry
 *       (BEFORE UPDATE/DELETE triggers, V21);</li>
 *   <li>one operation can produce only one journal entry - the DB unique
 *       constraint rejects a duplicate even when the application layer
 *       somehow tried;</li>
 *   <li>a corrupted balance projection is reported by the operator-facing
 *       reconciliation and left untouched for investigation.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JournalReconciliationIT {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired DataSource dataSource;
  @Autowired JournalEntryRepository entries;
  @Autowired ReconciliationService reconciliation;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void journalTablesAreAppendOnlyForTheApplicationRole() throws Exception {
    String alice = client.register("pgjrn-a@example.com", "PgJrn Alice");
    String aliceId = client.accountId(alice);
    long before = entries.count();
    client.deposit(alice, aliceId, "500.00");

    assertEquals(before + 1, entries.count(), "the deposit journaled exactly one entry");
    try (Connection c = dataSource.getConnection()) {
      try (var stmt = c.createStatement()) {
        // UPDATE and DELETE are both rejected by the append-only triggers.
        assertThrows(SQLException.class, () -> stmt.executeUpdate(
            "UPDATE journal_lines SET amount = amount WHERE 1 = 1"));
        assertThrows(SQLException.class, () -> stmt.executeUpdate(
            "DELETE FROM journal_lines WHERE 1 = 1"));
        assertThrows(SQLException.class, () -> stmt.executeUpdate(
            "DELETE FROM journal_entries WHERE 1 = 1"));
      }
    }
  }

  @Test
  void duplicateJournalForOneOperationIsRejectedByTheDatabase() throws Exception {
    String alice = client.register("pgjrn-b@example.com", "PgJrn Bob");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "250.00");

    // Take THIS deposit's journal identity, then try to book it again
    // directly - the (kind, operation_ref) uniqueness must refuse.
    try (Connection c = dataSource.getConnection()) {
      String operationRef = null;
      String kind = null;
      try (var stmt = c.createStatement();
          var rs = stmt.executeQuery("SELECT e.kind, e.operation_ref FROM journal_entries e "
              + "JOIN journal_lines l ON l.entry_id = e.id "
              + "WHERE e.kind = 'DEPOSIT' AND l.account_id = '" + aliceId + "' "
              + "ORDER BY e.seq DESC LIMIT 1")) {
        assertTrue(rs.next(), "deposit entry exists");
        kind = rs.getString(1);
        operationRef = rs.getString(2);
      }
      try (var stmt = c.prepareStatement(
          "INSERT INTO journal_entries (id, kind, operation_ref, currency, posted_at, memo) "
              + "VALUES (?, ?, ?, 'USD', now(), 'duplicate attempt')")) {
        stmt.setObject(1, UUID.randomUUID());
        stmt.setString(2, kind);
        stmt.setString(3, operationRef);
        assertThrows(SQLException.class, () -> stmt.executeUpdate());
      }
      assertEquals(1, entries.countByKindAndOperationRef(JournalKind.DEPOSIT, operationRef),
          "still exactly one journal entry for the operation");
    }
  }

  @Test
  void corruptedProjectionIsReportedAndNotSilentlyFixed() throws Exception {
    String alice = client.register("pgjrn-c@example.com", "PgJrn Carol");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "300.00");

    UUID accountId = UUID.fromString(aliceId);
    try (Connection c = dataSource.getConnection();
        var stmt = c.prepareStatement("UPDATE accounts SET balance = balance + 10.0000 WHERE id = ?")) {
      stmt.setObject(1, accountId);
      stmt.executeUpdate();
    }

    ReconciliationService.ReconciliationReport report = reconciliation.reconcile();
    assertFalse(report.balanced(), "drift must be reported");
    assertEquals(1, report.projectionDifferences().size());
    assertEquals(accountId.toString(), report.projectionDifferences().get(0).accountId());

    // The operator endpoint exposes it, and nothing was auto-repaired.
    MvcResult result = mvc.perform(get("/api/v1/admin/reconciliation")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk())
        .andReturn();
    assertFalse(objectMapper.readTree(result.getResponse().getContentAsString())
        .get("projectionDifferences").isEmpty());
    assertEquals(new BigDecimal("310.0000"),
        accounts.findById(accountId).orElseThrow().getBalance(), "reconciliation never mutates");
  }

  @Test
  @SuppressWarnings("unused")
  void heldApprovalJournalsExactlyOnceOnPostgres() throws Exception {
    String alice = client.register("pgjrn-d@example.com", "PgJrn Drew");
    String bob = client.register("pgjrn-e@example.com", "PgJrn Erin");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    long before = entries.count();
    client.deposit(alice, aliceId, "20000.00");
    String heldId = client.transferWithKey(alice, bobIban, "11000.00", "pg-held-1");
    assertEquals(before + 1, entries.count(), "the HELD intent journaled nothing");
    mvc.perform(post("/api/v1/admin/transactions/{id}/review", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    assertEquals(before + 2, entries.count(), "deposit + settled held transfer");
    assertEquals(1, entries.countByKindAndOperationRef(JournalKind.TRANSFER, heldId),
        "one journal for the approved operation");
  }
}
