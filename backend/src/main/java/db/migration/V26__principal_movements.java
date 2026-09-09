package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V26: immutable principal-component history (historical daily-principal
 * interest).
 *
 * <p>Loan interest is priced from the principal outstanding on each day of
 * the period, never from today's principal. That requires an immutable
 * record of what changed principal and when: draws (principal up),
 * principal repayments (principal down, interest extinguished first under the
(repayment policy) and the legacy cutover baseline. Every row carries
 * the financial transaction that produced it (unique per account + source, so
 * a movement can never be recorded twice), and rows are append-only like the
 * journal.
 *
 * <p>Legacy loans (created before this deployment) cannot prove their
 * day-by-day principal history, so each receives ONE {@code CUTOVER} baseline
 * row stamped at the migration instant. Periods wholly before that instant
 * are never priced: no fabricated history: and the first supported accrual
 * boundary for that account is the month containing the cutover instant.
 */
public class V26__principal_movements extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS principal_movements (\n"
          + "  id UUID PRIMARY KEY,\n"
          + "  account_id UUID NOT NULL REFERENCES accounts(id),\n"
          + "  kind VARCHAR(24) NOT NULL CHECK (kind IN ('DRAW','PRINCIPAL_REPAYMENT','CUTOVER')),\n"
          + "  amount NUMERIC(19,4) NOT NULL CHECK (amount <> 0),\n"
          + "  source_transaction_id UUID REFERENCES transactions(id),\n"
          + "  posted_at TIMESTAMP WITH TIME ZONE NOT NULL,\n"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP\n"
          + ")");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_principal_movements_account_posted "
          + "ON principal_movements (account_id, posted_at)");
    }
    // PostgreSQL gets the partial unique index (one movement per account +
    // source transaction); H2 has no partial-index support, so there the same
    // guarantee rests on the app writing at most one movement per transaction
    // in the owning service (the same division of labour as V15/V20).
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (product.contains("postgres")) {
      try (var stmt = connection.createStatement()) {
        stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_principal_movement_source "
            + "ON principal_movements (account_id, source_transaction_id) "
            + "WHERE source_transaction_id IS NOT NULL");
      }
    }

    // Legacy cutover baseline: one signed CUTOVER row per loan that holds
    // principal at deployment time. Fresh databases have no such rows.
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id, principal FROM accounts WHERE type = 'LOAN' AND principal > 0");
        ResultSet rows = select.executeQuery()) {
      try (PreparedStatement insert = connection.prepareStatement(
          "INSERT INTO principal_movements "
              + "(id, account_id, kind, amount, source_transaction_id, posted_at) "
              + "VALUES (?, ?, 'CUTOVER', ?, NULL, CURRENT_TIMESTAMP)")) {
        int count = 0;
        while (rows.next()) {
          UUID accountId = UUID.fromString(rows.getString(1));
          java.math.BigDecimal principal = rows.getBigDecimal(2);
          insert.setObject(1, UUID.randomUUID());
          insert.setObject(2, accountId);
          insert.setBigDecimal(3, principal);
          insert.addBatch();
          count++;
        }
        insert.executeBatch();
        if (count > 0) {
          // Log a bounded line so operators know a baseline boundary exists.
          System.out.println("V26: established CUTOVER principal baseline for " + count + " legacy loan account(s)");
        }
      }
    }
  }
}
