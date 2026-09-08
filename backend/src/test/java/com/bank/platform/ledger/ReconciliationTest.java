package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * reconciliation: balances must derive from the journal, not compare the
 * balance table with itself. A healthy ledger reconciles; a deliberately
 * corrupted projection or an injected unbalanced entry is REPORTED (never
 * silently repaired), and the operator-visible endpoint exposes the result.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReconciliationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired ReconciliationService reconciliation;
  @Autowired EntityManager entityManager;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void healthyLedgerReconcilesAndOperatorCanSeeIt() throws Exception {
    String alice = client.register("rec-a@example.com", "Rec Alice");
    String bob = client.register("rec-b@example.com", "Rec Bob");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "1000.00");
    client.transfer(alice, bobIban, "300.00");

    ReconciliationService.ReconciliationReport report = reconciliation.reconcile();
    assertTrue(report.balanced(), "healthy ledger: every balance derives from its lines");
    assertTrue(report.journalEntries() >= 2, "deposit + transfer both journaled");
    assertTrue(report.projectionDifferences().isEmpty());
    assertTrue(report.unbalancedEntries().isEmpty());
    assertTrue(report.duplicateOperations().isEmpty());
    assertTrue(report.currencyNets().isEmpty());

    mvc.perform(get("/api/v1/admin/reconciliation")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balanced").value(true))
        .andExpect(jsonPath("$.projectionDifferences").isEmpty());
  }

  @Test
  void corruptedProjectionIsReportedAndNeverSilentlyFixed() throws Exception {
    String alice = client.register("rec-c@example.com", "Rec C");
    String bob = client.register("rec-d@example.com", "Rec D");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");
    client.transfer(alice, bobIban, "50.00");

    // Corrupt Alice's projection: +1.00 with no journal line behind it. This
    // is exactly the drift reconciliation must catch.
    UUID id = UUID.fromString(aliceId);
    entityManager.createNativeQuery("update accounts set balance = balance + 1.0000 where id = :id")
        .setParameter("id", id)
        .executeUpdate();
    entityManager.flush();
    // The native UPDATE bypassed the persistence context - drop the cached
    // entities so the balance check below reads what the DB really holds.
    entityManager.clear();

    ReconciliationService.ReconciliationReport report = reconciliation.reconcile();
    assertFalse(report.balanced(), "a corrupted projection must be reported");
    assertEquals(1, report.projectionDifferences().size());
    ReconciliationService.AccountDifferenceRow row = report.projectionDifferences().get(0);
    assertEquals(id.toString(), row.accountId());

    // Reconciliation only reports: the corrupted row is untouched, exactly as
    // it was found, for an operator to investigate.
    BigDecimal balance = accounts.findById(id).orElseThrow().getBalance();
    assertEquals(new BigDecimal("451.0000"), balance, "reconcile must not mutate history");
  }

  @Test
  void injectedUnbalancedEntryIsFlagged() throws Exception {
    String alice = client.register("rec-e@example.com", "Rec E");
    UUID aliceAccount = UUID.fromString(client.accountId(alice));
    client.deposit(alice, aliceAccount.toString(), "100.00");

    // Tamper on H2 (no DB trigger there): an entry carrying a line with no
    // balancing partner. Reconciliation must flag it without touching it.
    UUID entryId = UUID.randomUUID();
    java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
    entityManager.createNativeQuery(
        "insert into journal_entries (id, kind, operation_ref, currency, posted_at, memo) "
            + "values (?, 'DEPOSIT', 'tampered-1', 'USD', ?, 'tampered')")
        .setParameter(1, entryId)
        .setParameter(2, now)
        .executeUpdate();
    entityManager.createNativeQuery(
        "insert into journal_lines (id, entry_id, account_id, counteraccount, amount, ordinal, posted_at) "
            + "values (?, ?, ?, null, 5.0000, 0, ?)")
        .setParameter(1, UUID.randomUUID())
        .setParameter(2, entryId)
        .setParameter(3, aliceAccount)
        .setParameter(4, now)
        .executeUpdate();
    entityManager.flush();

    ReconciliationService.ReconciliationReport report = reconciliation.reconcile();
    assertFalse(report.balanced(), "tampering must break the report");
    assertFalse(report.unbalancedEntries().isEmpty(), "the unbalanced entry is named");
  }
}
