package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V28: audit provenance consulted FIRST, independently of the current kind
 * label. V24's blind spot: it only searched the INTEREST_POSTED audit trail
 * on rows ALREADY labelled INTEREST, so an audit-proven engine posting whose
 * memo did not match V8's substring fell into a structural bucket: a
 * savings-interest credit became a DEPOSIT and a loan-interest charge stayed
 * a TRANSFER.
 *
 * <p>This test reproduces the full chain exactly like TransactionKindMigrationIT:
 * migrate a fresh database only to V7, seed realistic pre-kind rows (with and
 * without the engine's INTEREST audit), then migrate to the head. V8 guesses
 * by memo, V24 reclassifies from its (label-dependent) evidence: reproducing
 * the flawed outcomes: and V28 must correct both mislabels from the audit
 * trail, quarantine a contradictory two-sided audit row, and touch nothing
 * else.
 */
class KindAuditProvenanceMigrationIT {

  private static final String URL =
      "jdbc:h2:mem:kind28;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

  private static Connection keepAlive;

  @BeforeAll
  static void migrate() throws Exception {
    keepAlive = DriverManager.getConnection(URL, "sa", "");
    Flyway.configure().dataSource(URL, "sa", "").target("7").load().migrate();
    seed();
    Flyway.configure().dataSource(URL, "sa", "").load().migrate();
  }

  private static void seed() throws Exception {
    UUID user = UUID.randomUUID();
    UUID checking = UUID.randomUUID();
    UUID savings = UUID.randomUUID();
    UUID loan = UUID.randomUUID();
    try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
      exec(c, "INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
          + user + "', 'kind28@example.com', 'x', 'Kind 28')");
      account(c, checking, user, "DE20000000000000000001", "CHECKING");
      account(c, savings, user, "DE20000000000000000002", "SAVINGS");
      account(c, loan, user, "DE20000000000000000003", "LOAN");

      // Savings-interest credit with an INTEREST audit but a memo that does NOT
      // match V8's substring: V8 leaves it TRANSFER, V24 makes it a DEPOSIT: // V28 must restore it to INTEREST from the audit trail.
      UUID payout = UUID.randomUUID();
      tx(c, payout, null, savings, "Payout July");
      audit(c, user, payout);

      // Loan-interest charge with an INTEREST audit and a non-matching memo:
      // V24 never re-examines it, so it stays labelled TRANSFER: V28 must
      // promote it to INTEREST.
      UUID charge = UUID.randomUUID();
      tx(c, charge, loan, null, "September charge");
      audit(c, user, charge);

      // A two-sided row that CARRIES an INTEREST audit: V8 labels it INTEREST
      // (memo), V24 gives it AUDIT evidence without noticing the two sides.
      // V28 must quarantine the contradiction, not pick a side.
      UUID household = UUID.randomUUID();
      tx(c, household, checking, savings, "Household interest transfer");
      audit(c, user, household);

      // Innocent rows: untouched by V28.
      tx(c, UUID.randomUUID(), checking, savings, "Split the dinner bill");
      tx(c, UUID.randomUUID(), null, checking, "Refund from marketplace");
    }
  }

  private static void account(Connection c, UUID id, UUID user, String iban, String type)
      throws Exception {
    exec(c, "INSERT INTO accounts (id, user_id, iban, type, balance) VALUES ('"
        + id + "', '" + user + "', '" + iban + "', '" + type + "', 0)");
  }

  private static void tx(Connection c, UUID id, UUID from, UUID to, String memo)
      throws Exception {
    String fromSql = from == null ? "NULL" : "'" + from + "'";
    String toSql = to == null ? "NULL" : "'" + to + "'";
    exec(c, "INSERT INTO transactions (id, from_account_id, to_account_id, amount, memo) "
        + "VALUES ('" + id + "', " + fromSql + ", " + toSql + ", 10.0000, '"
        + memo.replace("'", "''") + "')");
  }

  private static void audit(Connection c, UUID actor, UUID transactionId) throws Exception {
    exec(c, "INSERT INTO audit_logs (actor_id, action, entity, entity_id) VALUES ('"
        + actor + "', 'INTEREST_POSTED', 'Transaction', '" + transactionId + "')");
  }

  private static void exec(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement()) {
      stmt.execute(sql);
    }
  }

  @Test
  void auditProvenanceOverridesTheV24StructuralLabels() throws Exception {
    Map<String, String[]> byMemo = new HashMap<>();
    try (Connection c = DriverManager.getConnection(URL, "sa", "");
        var stmt = c.createStatement();
        var rs = stmt.executeQuery(
            "SELECT memo, kind, kind_evidence FROM transactions")) {
      while (rs.next()) {
        byMemo.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
      }
    }
    String[] payout = byMemo.get("Payout July");
    assertEquals("INTEREST", payout[0],
        "audit-proven savings credit is interest, not V24's structural deposit");
    assertEquals("AUDIT", payout[1]);
    String[] charge = byMemo.get("September charge");
    assertEquals("INTEREST", charge[0], "audit-proven loan charge is interest, not a transfer");
    assertEquals("AUDIT", charge[1]);
    String[] household = byMemo.get("Household interest transfer");
    assertEquals("UNCERTAIN", household[1],
        "the contradictory two-sided AUDIT row is quarantined, not silently re-tagged");
    assertEquals("TRANSFER", byMemo.get("Split the dinner bill")[0]);
    assertEquals("STRUCTURE", byMemo.get("Split the dinner bill")[1]);
    assertEquals("DEPOSIT", byMemo.get("Refund from marketplace")[0]);
    assertEquals("STRUCTURE", byMemo.get("Refund from marketplace")[1]);
  }

  @Test
  void everyV28DecisionIsArchivedAndNothingElseIsAltered() throws Exception {
    try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
      // V24 archived the two from-null deposit corrections; V28 adds one new
      // archive per corrected/quarantined row (September charge and the
      // two-sided contradiction) and APPENDS to the already-archived Payout
      // row rather than erasing V24's first opinion.
      assertEquals(4L, scalar(c, "SELECT COUNT(*) FROM transaction_kind_review"));
      assertEquals(2L, scalar(c,
          "SELECT COUNT(*) FROM transaction_kind_review WHERE reason LIKE '%INTEREST_POSTED audit overrides%'"),
          "both audit-promoted rows carry the audit-backed reason (one appended to V24's archive)");
      assertEquals(1L, scalar(c,
          "SELECT COUNT(*) FROM transaction_kind_review WHERE reason LIKE '%contradictory evidence%'"));
      assertEquals(1L, scalar(c,
          "SELECT COUNT(*) FROM transaction_kind_review WHERE reason LIKE "
              + "'%from-null row is structurally a deposit%' AND reason LIKE '%INTEREST_POSTED audit%'"),
          "Payout July's archive keeps V24's first decision AND appends V28's correction");
      // Preservation: 5 seeded transactions, every memo/amount intact, and no
      // account balance moved.
      assertEquals(5L, scalar(c, "SELECT COUNT(*) FROM transactions"));
      assertEquals(0L, scalar(c, "SELECT COUNT(*) FROM transactions WHERE amount <> 10.0000"));
      assertEquals(0L, scalar(c, "SELECT SUM(balance) FROM accounts"));
      assertTrue(scalar(c,
          "SELECT COUNT(*) FROM transactions WHERE memo LIKE '%Payout%' "
              + "AND kind_evidence = 'AUDIT'") == 1L);
    }
  }

  private static long scalar(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement(); var rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
