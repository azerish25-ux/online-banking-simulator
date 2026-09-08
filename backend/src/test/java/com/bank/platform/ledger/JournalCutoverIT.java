package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Reconciled cutover against real PostgreSQL (disposable database only).
 *
 * <p>This test deliberately does NOT boot the Spring context. It migrates a
 * fresh database up to V21 (the journal schema, before the cutover runs),
 * seeds realistic pre-journal state - accounts whose balances were produced by
 * legacy transaction rows that no journal can reproduce with certainty - and
 * then migrates to the latest schema. The V22 cutover must book ONE labelled
 * OPENING_BALANCE entry per non-zero account against the MIGRATION_OPENING
 * counteraccount, preserve every legacy row untouched, and leave balances that
 * reconcile to their journal lines. V23 must then establish loan principal
 * from the cutover debt.
 */
class JournalCutoverIT {

  private static String url;
  private static String user;
  private static String password;

  @BeforeAll
  static void readPgSettings() {
    url = System.getProperty("it.pg.url");
    user = System.getProperty("it.pg.user");
    password = System.getProperty("it.pg.password");
    assertTrue(url != null && user != null && password != null,
        "run with -Dit.pg.url / -Dit.pg.user / -Dit.pg.password (a disposable database)");
  }

  @Test
  void cutoverBooksOpeningEntriesThatReconcileAndPreservesHistory() throws Exception {
    UUID owner = UUID.randomUUID();
    UUID checking = UUID.randomUUID();
    UUID savings = UUID.randomUUID();
    UUID loan = UUID.randomUUID();

    // 1) Migrate only up to the journal schema - the cutover has NOT run yet.
    Flyway.configure().dataSource(url, user, password).target("21").load().migrate();

    // 2) Seed pre-journal state: a user, three accounts with balances produced
    //    by legacy transactions, and the two legacy transaction rows.
    try (Connection c = DriverManager.getConnection(url, user, password)) {
      try (var stmt = c.prepareStatement(
          "INSERT INTO users (id, email, password_hash, full_name, role) VALUES (?, ?, ?, ?, 'CUSTOMER')")) {
        stmt.setObject(1, owner);
        stmt.setString(2, "cutover@example.com");
        stmt.setString(3, "x");
        stmt.setString(4, "Cutover");
        stmt.executeUpdate();
      }
      insertAccount(c, checking, owner, "DE00000000000000000001", "CHECKING",
          new BigDecimal("1500.0000"), BigDecimal.ZERO);
      insertAccount(c, savings, owner, "DE00000000000000000002", "SAVINGS",
          new BigDecimal("1600.0000"), BigDecimal.ZERO);
      insertAccount(c, loan, owner, "DE00000000000000000003", "LOAN",
          new BigDecimal("-400.0000"), new BigDecimal("1000.0000"));
      insertLegacyTransaction(c, UUID.randomUUID(), null, checking, "DEPOSIT",
          new BigDecimal("1500.0000"), "Simulated deposit");
      insertLegacyTransaction(c, UUID.randomUUID(), savings, loan, "TRANSFER",
          new BigDecimal("400.0000"), "Legacy draw");
      // Balances agree with the legacy rows: checking 1500, savings 1600, loan -400.
    }

    // 3) Migrate to the latest schema: V22 cutover + V23 loan principal.
    Flyway.configure().dataSource(url, user, password).load().migrate();

    try (Connection c = DriverManager.getConnection(url, user, password)) {
      // Every non-zero pre-journal account got exactly one labelled opening
      // entry, balancing customer line against MIGRATION_OPENING.
      assertEquals(3L, count(c,
          "SELECT COUNT(*) FROM journal_entries WHERE kind = 'OPENING_BALANCE'"),
          "one opening entry per non-zero account");
      assertEquals(3L, count(c,
          "SELECT COUNT(*) FROM journal_entries e "
              + "WHERE e.memo LIKE 'Cutover opening balance %V22 reconciled journal%'"),
          "cutover entries carry their provenance");
      assertEquals(3L, count(c,
          "SELECT COUNT(*) FROM journal_lines WHERE counteraccount = 'MIGRATION_OPENING'"));

      // Projections reconcile to their journal lines.
      assertEquals(0L, count(c,
          "SELECT COUNT(*) FROM ("
              + "  SELECT a.id FROM accounts a LEFT JOIN journal_lines l ON l.account_id = a.id "
              + "  GROUP BY a.id, a.balance "
              + "  HAVING a.balance <> COALESCE(SUM(l.amount), 0)) d"));

      // The loan's cutover debt became principal (V23), ready for interest.
      assertEquals(new BigDecimal("400.0000"), scalar(c,
          "SELECT principal FROM accounts WHERE id = '" + loan + "'"));

      // Legacy rows and identifiers are untouched - preserved, not re-written.
      assertEquals(2L, count(c, "SELECT COUNT(*) FROM transactions"), "legacy rows preserved");
      assertEquals(0L, count(c,
          "SELECT COUNT(*) FROM journal_entries WHERE kind <> 'OPENING_BALANCE'"),
          "no fabricated historical postings beyond the labelled opening entries");
    }
  }

  private static void insertAccount(Connection c, UUID id, UUID owner, String iban,
      String type, BigDecimal balance, BigDecimal creditLimit) throws Exception {
    try (var stmt = c.prepareStatement(
        "INSERT INTO accounts (id, user_id, iban, type, balance, status, credit_limit) "
            + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)")) {
      stmt.setObject(1, id);
      stmt.setObject(2, owner);
      stmt.setString(3, iban);
      stmt.setString(4, type);
      stmt.setBigDecimal(5, balance);
      stmt.setBigDecimal(6, creditLimit);
      stmt.executeUpdate();
    }
  }

  private static void insertLegacyTransaction(Connection c, UUID id, UUID from, UUID to,
      String kind, BigDecimal amount, String memo) throws Exception {
    try (var stmt = c.prepareStatement(
        "INSERT INTO transactions (id, from_account_id, to_account_id, amount, kind, status, memo, "
            + "created_at, posted_at) VALUES (?, ?, ?, ?, ?, 'POSTED', ?, now(), now())")) {
      stmt.setObject(1, id);
      if (from == null) {
        stmt.setObject(2, null);
      } else {
        stmt.setObject(2, from);
      }
      stmt.setObject(3, to);
      stmt.setBigDecimal(4, amount);
      stmt.setString(5, kind);
      stmt.setString(6, memo);
      stmt.executeUpdate();
    }
  }

  private static long count(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement(); var rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static BigDecimal scalar(Connection c, String sql) throws Exception {
    try (var stmt = c.createStatement(); var rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getBigDecimal(1);
    }
  }
}
