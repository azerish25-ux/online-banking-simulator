package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * loan principal tracked separately from accrued interest, and per-account
 * accrual bookkeeping.
 *
 * <ul>
 *   <li>{@code accounts.principal} - outstanding drawn principal on a LOAN
 *       (0..credit_limit), independent of the interest that has accrued on it.
 *       Draws consume principal headroom; interest deepens the balance without
 *       touching principal, so a loan at its limit is NEVER silently forgiven
 *       interest. Repayments extinguish interest first, then principal, and
 *       are capped at the amount owed (a loan balance never goes positive).</li>
 *   <li>{@code interest_accruals} - one row per account/accrual-period, unique
 *       at the database level, so a scheduler run and an admin trigger can
 *       never post the same account-month twice. A failed batch resumes: the
 *       accounts that committed keep their rows, the next run only takes the
 *       rest.</li>
 * </ul>
 *
 * <p>Legacy loans: existing balances were the sum of principal and whatever
 * had accrued under the old model, so each is established as principal at
 * cutover (the debt is honoured; nothing is forgiven or invented).
 */
public class V23__loan_principal_and_interest_accruals extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection() - Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("ALTER TABLE accounts ADD COLUMN IF NOT EXISTS principal NUMERIC(19,4) "
          + "NOT NULL DEFAULT 0");
      stmt.execute("CREATE TABLE IF NOT EXISTS interest_accruals (\n"
          + "  id UUID PRIMARY KEY,\n"
          + "  account_id UUID NOT NULL REFERENCES accounts(id),\n"
          + "  period VARCHAR(7) NOT NULL,\n"
          + "  rate_version INT NOT NULL,\n"
          + "  status VARCHAR(16) NOT NULL DEFAULT 'POSTED' CHECK (status = 'POSTED'),\n"
          + "  amount NUMERIC(19,4) NOT NULL,\n"
          + "  basis NUMERIC(19,4) NOT NULL,\n"
          + "  day_count INT NOT NULL,\n"
          + "  posted_at TIMESTAMP WITH TIME ZONE NOT NULL,\n"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
          + "  CONSTRAINT uq_accrual_account_period UNIQUE (account_id, period)\n"
          + ")");
      // Legacy debt becomes principal at cutover (policy).
      stmt.execute("UPDATE accounts SET principal = "
          + "CASE WHEN type = 'LOAN' AND balance < 0 THEN -balance ELSE 0 END");
      // The old limit check capped the BALANCE (so a maxed loan forgave its
      // interest). Principal availability is now tracked separately: interest
      // may push the balance past -credit_limit; draws may not push principal
      // past it; a loan balance never goes positive.
      stmt.execute("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_loan_within_limit");
      stmt.execute("ALTER TABLE accounts ADD CONSTRAINT accounts_loan_within_limit "
          + "CHECK (type <> 'LOAN' OR (balance <= 0 AND principal >= 0 "
          + "AND principal <= credit_limit AND balance + principal <= 0))");
      stmt.execute("ALTER TABLE accounts ADD CONSTRAINT accounts_non_loan_no_principal "
          + "CHECK (type = 'LOAN' OR principal = 0)");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_accruals_account "
          + "ON interest_accruals (account_id, period)");
    }
  }
}
