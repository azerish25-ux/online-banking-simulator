package db.migration;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V29: the authorized reversal workflow.
 *
 * <p>A posted instruction is never edited or deleted - history is history. An
 * operator can REVERSE it: a NEW {@code REVERSAL} transaction row that moves
 * the money back (mirroring the original legs), a linked {@code REVERSAL}
 * journal entry whose {@code reverses_entry_id} points at the original
 * entry, and an audit record carrying the actor and the reason. The original
 * row keeps its POSTED state and its own history.
 *
 * <ul>
 *   <li>{@code transactions.reverses_transaction_id} - the original row this
 *       reversal undoes. PostgreSQL enforces at most one reversal per original
 *       (partial unique index); on H2 the service checks it before writing.</li>
 *   <li>{@code transactions.reversal_reason} - the mandatory operator reason,
 *       preserved on the reversal row itself (not only in the audit log).</li>
 *   <li>The kind CHECKs on {@code transactions} and {@code journal_entries}
 *       both gain {@code REVERSAL}.</li>
 * </ul>
 */
public class V29__reversal_workflow extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    // 1) transactions: kind CHECK gains REVERSAL; two new columns.
    dropKindChecks(connection, "TRANSACTIONS");
    exec(connection,
        "ALTER TABLE transactions ADD CONSTRAINT transactions_kind_check "
            + "CHECK (kind IN ('TRANSFER', 'DEPOSIT', 'INTEREST', 'REVERSAL'))");
    exec(connection, "ALTER TABLE transactions "
        + "ADD COLUMN IF NOT EXISTS reverses_transaction_id UUID REFERENCES transactions(id)");
    exec(connection, "ALTER TABLE transactions "
        + "ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(255)");

    // 2) journal_entries: kind CHECK gains REVERSAL.
    dropKindChecks(connection, "JOURNAL_ENTRIES");
    exec(connection,
        "ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_kind_check "
            + "CHECK (kind IN ('DEPOSIT', 'TRANSFER', 'INTEREST', 'OPENING_BALANCE', 'REVERSAL'))");

    // 3) PostgreSQL: one reversal per original transaction. H2 has no partial
    //    index support - there the app-level exists-check is the guard (the
    //    same division of labour as V15/V20/V26).
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (product.contains("postgres")) {
      exec(connection,
          "CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_one_reversal "
              + "ON transactions (reverses_transaction_id) WHERE reverses_transaction_id IS NOT NULL");
    }
  }

  /** Drops every CHECK constraint touching the KIND column (unnamed legacy CHECKs). */
  private static void dropKindChecks(Connection connection, String tableName) throws Exception {
    List<String> toDrop = new ArrayList<>();
    try (var ps = connection.prepareStatement(
        "SELECT ccu.constraint_name "
            + "FROM information_schema.constraint_column_usage ccu "
            + "JOIN information_schema.table_constraints tc "
            + "ON tc.constraint_name = ccu.constraint_name "
            + "AND tc.constraint_schema = ccu.constraint_schema "
            + "WHERE UPPER(ccu.table_name) = ? AND UPPER(ccu.column_name) = 'KIND' "
            + "AND tc.constraint_type = 'CHECK'")) {
      ps.setString(1, tableName);
      var rs = ps.executeQuery();
      while (rs.next()) {
        toDrop.add(rs.getString(1));
      }
    }
    for (String name : toDrop) {
      exec(connection, "ALTER TABLE " + tableName
          + " DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
    }
  }

  private static void exec(Connection connection, String sql) throws Exception {
    try (var stmt = connection.createStatement()) {
      stmt.execute(sql);
    }
  }
}
