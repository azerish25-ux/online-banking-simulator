package db.migration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V1 declared an unnamed {@code balance >= 0} check, which blocks loan overdrafts.
 * Unnamed checks get database-generated names, so this migration discovers and drops
 * every CHECK touching accounts.balance via INFORMATION_SCHEMA, then installs two
 * explicitly-named, loan-aware checks. Runs on PostgreSQL and H2.
 */
public class V6__loan_balance_checks extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection() - Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    {
      List<String> toDrop = new ArrayList<>();
      try (var ps = connection.prepareStatement(
          "SELECT ccu.constraint_name "
              + "FROM information_schema.constraint_column_usage ccu "
              + "JOIN information_schema.table_constraints tc "
              + "ON tc.constraint_name = ccu.constraint_name "
              + "AND tc.constraint_schema = ccu.constraint_schema "
              + "WHERE UPPER(ccu.table_name) = 'ACCOUNTS' "
              + "AND UPPER(ccu.column_name) = 'BALANCE' "
              + "AND tc.constraint_type = 'CHECK'")) {
        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
          toDrop.add(rs.getString(1));
        }
      }
      try (var stmt = connection.createStatement()) {
        for (String name : toDrop) {
          stmt.execute("ALTER TABLE accounts DROP CONSTRAINT \"" + name.replace("\"", "") + "\"");
        }
        stmt.execute("ALTER TABLE accounts ADD CONSTRAINT accounts_balance_nonnegative "
            + "CHECK (balance >= 0 OR type = 'LOAN')");
        stmt.execute("ALTER TABLE accounts ADD CONSTRAINT accounts_loan_within_limit "
            + "CHECK (type <> 'LOAN' OR balance >= -credit_limit)");
      }
    }
  }
}
