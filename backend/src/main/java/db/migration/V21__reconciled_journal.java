package db.migration;

import java.sql.Connection;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * the reconciled journal. Two append-only tables separate an
 * operation/instruction's lifecycle (the existing {@code transactions} rows)
 * from the immutable posted accounting entries that actually moved money:
 *
 * <ul>
 *   <li>{@code journal_entries}: one balanced group per posted operation:
 *   unique entry identity (id), the operation reference (the originating
 *   transaction row id), currency, posting timestamp, a deterministic
 *   ordering key (the DB-assigned {@code seq}), and provenance memo. The
 *   (kind, operation_ref) uniqueness makes a duplicate retry unable to create
 *   a second journal for the same operation.</li>
 *   <li>{@code journal_lines}: the signed postings of an entry. Each line
 *   names exactly one side: a customer {@code account_id} (a projection
 *   account) or a named {@code counteraccount} (the simulator-funding,
 *   interest, or migration-opening side). Signed deltas sum to zero per
 *   entry; customer balances are projections that reconcile to their lines.</li>
 * </ul>
 *
 * <p>Append-only is enforced with BEFORE UPDATE/DELETE triggers on PostgreSQL
 * (the production database), so even the application role cannot edit or
 * delete a posted entry: a correction is a NEW linked entry, never a
 * mutation. H2 has no portable trigger for this migration, so there the
 * append-only contract is exercised through the service/API surface and the
 * reconciliation routine detects any tampering that repository conventions
 * cannot prevent (see ReconciliationService and its tests).
 */
public class V21__reconciled_journal extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection(): Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS journal_entries (\n"
          + "  id UUID PRIMARY KEY,\n"
          + "  kind VARCHAR(24) NOT NULL\n"
          + "    CHECK (kind IN ('DEPOSIT', 'TRANSFER', 'INTEREST', 'OPENING_BALANCE')),\n"
          + "  operation_ref VARCHAR(80) NOT NULL,\n"
          + "  currency VARCHAR(3) NOT NULL DEFAULT 'USD',\n"
          + "  posted_at TIMESTAMP WITH TIME ZONE NOT NULL,\n"
          + "  memo VARCHAR(255),\n"
          + "  reverses_entry_id UUID REFERENCES journal_entries(id),\n"
          + "  seq BIGINT GENERATED ALWAYS AS IDENTITY,\n"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,\n"
          + "  CONSTRAINT uq_journal_operation UNIQUE (kind, operation_ref)\n"
          + ")");
      stmt.execute("CREATE TABLE IF NOT EXISTS journal_lines (\n"
          + "  id UUID PRIMARY KEY,\n"
          + "  entry_id UUID NOT NULL REFERENCES journal_entries(id),\n"
          + "  account_id UUID REFERENCES accounts(id),\n"
          + "  counteraccount VARCHAR(32)\n"
          + "    CHECK (counteraccount IN ('SIMULATOR_FUNDING', 'INTEREST', 'MIGRATION_OPENING')),\n"
          + "  amount NUMERIC(19,4) NOT NULL,\n"
          + "  ordinal INT NOT NULL,\n"
          + "  posted_at TIMESTAMP WITH TIME ZONE NOT NULL,\n"
          + "  CONSTRAINT journal_line_exactly_one_side CHECK ((account_id IS NULL) <> (counteraccount IS NULL)),\n"
          + "  CONSTRAINT uq_journal_line_ordinal UNIQUE (entry_id, ordinal)\n"
          + ")");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_journal_lines_account "
          + "ON journal_lines (account_id, posted_at)");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_journal_lines_entry "
          + "ON journal_lines (entry_id)");
      stmt.execute("CREATE INDEX IF NOT EXISTS idx_journal_entries_posted "
          + "ON journal_entries (posted_at)");
    }
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
    if (!product.contains("postgres")) {
      return;
    }
    try (var stmt = connection.createStatement()) {
      stmt.execute("CREATE OR REPLACE FUNCTION journal_entries_append_only() RETURNS trigger AS $$\n"
          + "BEGIN\n"
          + "  RAISE EXCEPTION 'journal_entries is append-only: postings are immutable';\n"
          + "END;\n"
          + "$$ LANGUAGE plpgsql");
      stmt.execute("CREATE OR REPLACE FUNCTION journal_lines_append_only() RETURNS trigger AS $$\n"
          + "BEGIN\n"
          + "  RAISE EXCEPTION 'journal_lines is append-only: postings are immutable';\n"
          + "END;\n"
          + "$$ LANGUAGE plpgsql");
      stmt.execute("DROP TRIGGER IF EXISTS trg_journal_entries_append_only ON journal_entries");
      stmt.execute("DROP TRIGGER IF EXISTS trg_journal_lines_append_only ON journal_lines");
      stmt.execute("CREATE TRIGGER trg_journal_entries_append_only "
          + "BEFORE UPDATE OR DELETE ON journal_entries "
          + "FOR EACH ROW EXECUTE FUNCTION journal_entries_append_only()");
      stmt.execute("CREATE TRIGGER trg_journal_lines_append_only "
          + "BEFORE UPDATE OR DELETE ON journal_lines "
          + "FOR EACH ROW EXECUTE FUNCTION journal_lines_append_only()");
    }
  }
}
