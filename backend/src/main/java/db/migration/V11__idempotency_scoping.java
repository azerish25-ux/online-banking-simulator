package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * Idempotency keys belong to the transfer's originating account, not to the
 * whole table. V1 declared a column-level UNIQUE on transactions.idempotency_key,
 * which (a) let two unrelated customers collide on the same key string and (b)
 * let a replay of a foreign key reveal that it existed. This migration drops
 * that auto-named constraint (discovered via INFORMATION_SCHEMA, like V6) and
 * installs a unique index on (from_account_id, idempotency_key): two users may
 * both use "pay-rent", while one user can never post two transfers with the
 * same key from the same account. Runs on PostgreSQL and H2.
 */
public class V11__idempotency_scoping extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection() - Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();

    List<String> uniqueConstraints = new ArrayList<>();
    // constraint_column_usage (not key_column_usage) is present on both
    // PostgreSQL and H2; the same discovery pattern powers V6.
    try (var ps = connection.prepareStatement(
        "SELECT ccu.constraint_name "
            + "FROM information_schema.constraint_column_usage ccu "
            + "JOIN information_schema.table_constraints tc "
            + "ON tc.constraint_name = ccu.constraint_name "
            + "AND tc.constraint_schema = ccu.constraint_schema "
            + "WHERE UPPER(ccu.table_name) = 'TRANSACTIONS' "
            + "AND UPPER(ccu.column_name) = 'IDEMPOTENCY_KEY' "
            + "AND tc.constraint_type = 'UNIQUE'")) {
      ResultSet rs = ps.executeQuery();
      while (rs.next()) {
        uniqueConstraints.add(rs.getString(1));
      }
    }
    try (var stmt = connection.createStatement()) {
      for (String name : uniqueConstraints) {
        stmt.execute("ALTER TABLE transactions DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
      }
      stmt.execute("CREATE UNIQUE INDEX uq_transactions_idem_from "
          + "ON transactions (from_account_id, idempotency_key)");
    }
  }
}
