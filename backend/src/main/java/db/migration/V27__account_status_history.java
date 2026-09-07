package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V27: append-only account status history.
 *
 * <p>Interest must not be charged for days an account was frozen, and a frozen
 * period must not be retroactively charged after re-activation. The interest
 * job therefore prices each calendar day only if the account was ACTIVE that
 * day. That requires knowing when status actually changed - the audit log is
 * not a queryable schedule - so every FROZEN/ACTIVE transition written by
 * operations is recorded here, in the same transaction as the status change.
 * Rows are immutable: nothing edits or deletes a transition.
 */
public class V27__account_status_history extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS account_status_history (\n"
          + "  id UUID PRIMARY KEY,\n"
          + "  account_id UUID NOT NULL REFERENCES accounts(id),\n"
          + "  status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','FROZEN')),\n"
          + "  changed_at TIMESTAMP WITH TIME ZONE NOT NULL,\n"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP\n"
          + ")");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_status_history_account_changed "
          + "ON account_status_history (account_id, changed_at)");
    }
  }
}
