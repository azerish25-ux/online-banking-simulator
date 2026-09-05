package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * One LOAN account per user. Without the cap a customer could mint unbounded
 * $1,000 credit by opening loan after loan and transferring the proceeds out.
 * PostgreSQL gets a partial unique index (one row per user, LOAN rows only);
 * H2 has no partial-index support, so there the same rule is enforced by
 * {@code AccountService} - the app-level check, not this migration, is what
 * the tests exercise either way.
 */
public class V15__one_open_loan_per_user extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection() - Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (!product.contains("postgres")) {
      return;
    }
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_one_open_loan "
          + "ON accounts (user_id) WHERE type = 'LOAN'");
    }
  }
}
