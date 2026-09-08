package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * operation identity. Every keyed operation records a canonical payload
 * hash ({@code request_hash}) - source, destination, exact normalized amount,
 * currency, and normalized memo - so an idempotent replay can be told apart
 * from a *conflicting* reuse of the same key (same key, different intent is a
 * conflict, never a silent replay of the older row).
 *
 * <p>Transfers were already scoped by {@code (from_account_id,
 * idempotency_key)} (V11). Deposits get the mirror-image guarantee here: a
 * deposit key belongs to the account it funds, and only rows without an
 * originator ({@code from_account_id IS NULL} - i.e. deposits) participate.
 * PostgreSQL gets a partial unique index; H2 has no partial-index support, so
 * there the same rule is enforced by {@code MoneyService} - the app-level
 * check, not this migration, is what the H2 tests exercise either way (the
 * same division of labour as V15's one-loan-per-user rule).
 */
public class V20__operation_identity extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection() - Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS request_hash VARCHAR(64)");
    }
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (!product.contains("postgres")) {
      return;
    }
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_idem_deposit "
          + "ON transactions (to_account_id, idempotency_key) WHERE from_account_id IS NULL");
    }
  }
}
