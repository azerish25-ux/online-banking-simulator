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
 * forward-only kind reclassification, chained over a PRE-V8 fixture.
 *
 * <p>The V8 migration (checksummed, never edited) classified legacy rows by
 * memo substrings. This test migrates a fresh database only to V7, seeds
 * realistic pre-kind rows: engine interest WITH and WITHOUT an audit trail, a
 * genuine user transfer whose memo merely says \"interest\", deposits whose
 * memos do and do not match V8's pattern: then migrates to the head. V24
 * must correct from AUDIT/STRUCTURE evidence, archive every decision in
 * {@code transaction_kind_review}, label unprovable rows UNCERTAIN, and never
 * change a balance, amount, memo, or identifier.
 */
class TransactionKindMigrationIT {

  private static final String URL =
      "jdbc:h2:mem:kindchain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";

  @BeforeAll
  static void migrate() throws Exception {
    // Up to V7: transactions has no kind column yet (V8 introduces it).
    Flyway.configure().dataSource(URL, "sa", "").target("7").load().migrate();
    seed();
    // V8 (memo classification) ... V24 (evidence reclassification).
    Flyway.configure().dataSource(URL, "sa", "").load().migrate();
  }

  private static void seed() throws Exception {
    UUID user = UUID.randomUUID();
    UUID checking = UUID.randomUUID();
    UUID savings = UUID.randomUUID();
    UUID loan = UUID.randomUUID();
    try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
      exec(c, "INSERT INTO users (id, email, password_hash, full_name) VALUES ('"
          + user + "', 'kindchain@example.com', 'x', 'Kind Chain')");
      exec(c, "INSERT INTO accounts (id, user_id, iban, type, balance) VALUES ('"
          + checking + "', '" + user + "', 'DE10000000000000000001', 'CHECKING', 0)");
      exec(c, "INSERT INTO accounts (id, user_id, iban, type, balance) VALUES ('"
          + savings + "', '" + user + "', 'DE10000000000000000002', 'SAVINGS', 0)");
      exec(c, "INSERT INTO accounts (id, user_id, iban, type, balance) VALUES ('"
          + loan + "', '" + user + "', 'DE10000000000000000003', 'LOAN', 0)");

      // A: engine loan interest WITH the INTEREST_POSTED audit trail. (V7 has
      // no kind column: V8's memo classifier will label it INTEREST.)
      UUID a = UUID.randomUUID();
      tx(c, a, loan, null, "Loan interest May");
      exec(c, "INSERT INTO audit_logs (actor_id, action, entity, entity_id) VALUES ('"
          + user + "', 'INTEREST_POSTED', 'Transaction', '" + a + "')");

      // B: a genuine user transfer whose memo merely contains \"interest\".
      tx(c, UUID.randomUUID(), checking, savings, "Monthly interest to the household");

      // C: a deposit whose memo matches V8's Simulated deposit pattern.
      tx(c, UUID.randomUUID(), null, checking, "Simulated deposit");

      // E: a deposit with an unmatched memo (V8 left it as TRANSFER).
      tx(c, UUID.randomUUID(), null, checking, "Refund from marketplace");

      // F: savings-interest-shaped credit with NO audit trail (ambiguous).
      tx(c, UUID.randomUUID(), null, savings, "interest payout");

      // G: an ordinary transfer, no keyword.
      tx(c, UUID.randomUUID(), checking, savings, "Split the dinner bill");
    }
  }

  private static void tx(Connection c, UUID id, UUID from, UUID to, String memo)
      throws Exception {
    String fromSql = from == null ? "NULL" : "'" + from + "'";
    String toSql = to == null ? "NULL" : "'" + to + "'";
    exec(c, "INSERT INTO transactions (id, from_account_id, to_account_id, amount, memo) "
        + "VALUES ('" + id + "', " + fromSql + ", " + toSql + ", 10.0000, '"
        + memo.replace("'", "''") + "')");
  }

  private static void exec(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement()) {
      stmt.execute(sql);
    }
  }

  @Test
  void auditAndStructuralEvidenceCorrectTheMemoClassification() throws Exception {
    Map<String, String[]> expected = Map.of(
        // Row → {kind, kind_evidence}
        "kindA", new String[] {"INTEREST", "AUDIT"},
        "kindB", new String[] {"TRANSFER", "STRUCTURE"},
        "kindC", new String[] {"DEPOSIT", "STRUCTURE"},
        "kindE", new String[] {"DEPOSIT", "STRUCTURE"},
        "kindF", new String[] {"INTEREST", "UNCERTAIN"},
        "kindG", new String[] {"TRANSFER", "STRUCTURE"});
    try (Connection c = DriverManager.getConnection(URL, "sa", "");
        var stmt = c.createStatement();
        var rs = stmt.executeQuery(
            "SELECT id, kind, kind_evidence FROM transactions ORDER BY id")) {
      Map<String, String> byKey = new HashMap<>();
      while (rs.next()) {
        byKey.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3));
      }
      // Resolve fixture ids by memo so expectations stay readable.
      Map<String, String> memoKey = Map.of(
          "Loan interest May", "kindA",
          "Monthly interest to the household", "kindB",
          "Simulated deposit", "kindC",
          "Refund from marketplace", "kindE",
          "interest payout", "kindF",
          "Split the dinner bill", "kindG");
      try (var m = c.createStatement();
          var mrs = m.executeQuery("SELECT memo, id FROM transactions")) {
        while (mrs.next()) {
          String key = memoKey.get(mrs.getString(1));
          if (key != null) {
            String actual = byKey.get(mrs.getString(2));
            String wanted = expected.get(key)[0] + "|" + expected.get(key)[1];
            assertEquals(wanted, actual, "row [" + key + "] (" + mrs.getString(1) + ")");
          }
        }
      }
    }
  }

  @Test
  void everyDecisionIsArchivedAndNothingIsAltered() throws Exception {
    try (Connection c = DriverManager.getConnection(URL, "sa", "")) {
      // Exactly three decisions are worth an operator's eyes: the two-sided
      // INTEREST mislabel (B), the from-null non-deposit label (E) and the
      // unprovable engine-shaped row (F).
      assertEquals(3L, scalar(c, "SELECT COUNT(*) FROM transaction_kind_review"));

      // B's archive keeps the ORIGINAL V8 classification (INTEREST) with the
      // memo: evidence preserved, never erased to make reports fit.
      assertEquals(1L, scalar(c,
          "SELECT COUNT(*) FROM transaction_kind_review r JOIN transactions t "
              + "ON CAST(r.transaction_id AS VARCHAR) = CAST(t.id AS VARCHAR) "
              + "WHERE t.memo = 'Monthly interest to the household' "
              + "AND r.prior_kind = 'INTEREST'"));
      assertTrue(scalar(c, "SELECT COUNT(*) FROM transaction_kind_review "
          + "WHERE reason LIKE '%UNCERTAIN%' OR reason LIKE '%no INTEREST_POSTED audit%'") == 1L
          || scalar(c, "SELECT COUNT(*) FROM transaction_kind_review "
              + "WHERE reason LIKE '%interest-shaped%'") == 1L,
          "the ambiguous row is quarantined with its reason");

      // Preservation: 6 seeded transactions, every id and memo intact, and
      // balances/amounts untouched.
      assertEquals(6L, scalar(c, "SELECT COUNT(*) FROM transactions"));
      assertEquals(0L, scalar(c, "SELECT SUM(balance) FROM accounts"));
      assertEquals(0L, scalar(c,
          "SELECT COUNT(*) FROM transactions WHERE amount <> 10.0000 OR memo IS NULL"));
    }
  }

  private static long scalar(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement(); var rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
