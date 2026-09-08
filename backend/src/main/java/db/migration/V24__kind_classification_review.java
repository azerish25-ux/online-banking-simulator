package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * forward-only, evidence-backed transaction-kind review.
 *
 * <p>V8 (a checksummed, already-applied migration) classified every legacy
 * row by memo SUBSTRINGS - {@code memo LIKE '%interest%'} labelled an
 * ordinary user transfer whose memo merely mentioned \"interest\" as an
 * interest posting, and {@code memo LIKE 'Simulated deposit%'} left a deposit
 * with any other memo as TRANSFER. Historical migrations are never edited, so
 * this migration CORRECTS FORWARD with evidence, never guesses:
 *
 * <ol>
 *   <li><b>Audit provenance</b> - a row whose id appears in an
 *       {@code INTEREST_POSTED} audit entry is engine interest (the interest
 *       job audits every posting by transaction id).</li>
 *   <li><b>Structure</b> - the interest engine never writes BOTH sides
 *       (savings credits are to-only, loan charges are from-only), and only
 *       deposits write {@code from_account_id IS NULL}; a two-sided row
 *       labelled INTEREST by a memo substring is therefore a transfer, and a
 *       from-null non-interest row is a deposit.</li>
 *   <li><b>Quarantine, don't fabricate</b> - every correction is archived in
 *       {@code transaction_kind_review} (original V8 classification, reason,
 *       memo excerpt preserved); genuinely ambiguous rows (engine-shaped but
 *       without audit provenance) are LABELLED {@code UNCERTAIN} in
 *       {@code kind_evidence} and listed for operator review. No balance,
 *       amount, memo or identifier is ever changed; the original memo-based
 *       classification survives as the row's {@code LEGACY} evidence plus the
 *       review archive.
 * </ol>
 *
 * <p>Postcondition: {@code kind_evidence} is set for every row; no row is
 * reclassified without AUDIT or STRUCTURE provenance or moved to UNCERTAIN
 * without an archive entry; application startup and counts are unchanged.
 */
public class V24__kind_classification_review extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();
    try (var stmt = connection.createStatement()) {
      stmt.execute("ALTER TABLE transactions ADD COLUMN IF NOT EXISTS kind_evidence VARCHAR(16) "
          + "NOT NULL DEFAULT 'LEGACY'");
      stmt.execute("CREATE TABLE IF NOT EXISTS transaction_kind_review (\n"
          + "  transaction_id UUID PRIMARY KEY REFERENCES transactions(id),\n"
          + "  prior_kind VARCHAR(16) NOT NULL,\n"
          + "  reason VARCHAR(255) NOT NULL,\n"
          + "  memo_snippet VARCHAR(140),\n"
          + "  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP\n"
          + ")");
    }

    // 1) Audit provenance: the interest engine audited every posting it made.
    exec(connection,
        "UPDATE transactions t SET kind_evidence = 'AUDIT' "
            + "WHERE t.kind = 'INTEREST' AND EXISTS (SELECT 1 FROM audit_logs a "
            + "WHERE a.action = 'INTEREST_POSTED' AND a.entity = 'Transaction' "
            + "AND a.entity_id = CAST(t.id AS VARCHAR))");

    // 2) Structural: two-sided rows are transfers (the engine never writes two
    //    sides) - including V8's memo-INTEREST mislabels.
    archive(connection,
        "SELECT t.id, t.kind FROM transactions t "
            + "WHERE t.kind = 'INTEREST' AND t.kind_evidence = 'LEGACY' "
            + "AND t.from_account_id IS NOT NULL AND t.to_account_id IS NOT NULL",
        "memo-substring 'interest' on a two-sided row: structural transfer");
    exec(connection,
        "UPDATE transactions t SET kind = 'TRANSFER', kind_evidence = 'STRUCTURE' "
            + "WHERE t.kind = 'INTEREST' AND t.kind_evidence = 'LEGACY' "
            + "AND t.from_account_id IS NOT NULL AND t.to_account_id IS NOT NULL");
    exec(connection,
        "UPDATE transactions t SET kind_evidence = 'STRUCTURE' "
            + "WHERE t.kind = 'TRANSFER' AND t.kind_evidence = 'LEGACY' "
            + "AND t.from_account_id IS NOT NULL AND t.to_account_id IS NOT NULL");

    // 3) Structural: from-null rows that are not interest are deposits
    //    (only deposits fund with no originator; V20 enforces their identity).
    archive(connection,
        "SELECT t.id, t.kind FROM transactions t "
            + "WHERE t.from_account_id IS NULL AND t.to_account_id IS NOT NULL "
            + "AND t.kind_evidence = 'LEGACY' AND t.kind <> 'INTEREST' "
            + "AND t.kind <> 'DEPOSIT'",
        "from-null row is structurally a deposit");
    exec(connection,
        "UPDATE transactions t SET kind = 'DEPOSIT', kind_evidence = 'STRUCTURE' "
            + "WHERE t.from_account_id IS NULL AND t.to_account_id IS NOT NULL "
            + "AND t.kind_evidence = 'LEGACY' AND t.kind <> 'INTEREST'");

    // 4) Remaining engine-shaped interest without audit provenance is
    //    ambiguous: labelled for operator review, never silently re-tagged.
    archive(connection,
        "SELECT t.id, t.kind FROM transactions t "
            + "WHERE t.kind = 'INTEREST' AND t.kind_evidence = 'LEGACY'",
        "interest-shaped but no INTEREST_POSTED audit trail");
    exec(connection,
        "UPDATE transactions t SET kind_evidence = 'UNCERTAIN' "
            + "WHERE t.kind = 'INTEREST' AND t.kind_evidence = 'LEGACY'");

    // 5) Deposits and transfers whose memo already matched (V8 guess confirmed
    //    by structure) get STRUCTURE evidence rather than staying LEGACY.
    exec(connection,
        "UPDATE transactions t SET kind_evidence = 'STRUCTURE' "
            + "WHERE t.kind = 'DEPOSIT' AND t.kind_evidence = 'LEGACY' "
            + "AND t.from_account_id IS NULL AND t.to_account_id IS NOT NULL");
  }

  private void archive(Connection connection, String selectSql, String reason) throws Exception {
    List<Object[]> rows = new ArrayList<>();
    try (PreparedStatement ps = connection.prepareStatement(selectSql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        rows.add(new Object[] {rs.getString(1), rs.getString(2)});
      }
    }
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO transaction_kind_review (transaction_id, prior_kind, reason, memo_snippet) "
            + "SELECT id, ?, ?, SUBSTRING(memo, 1, 140) FROM transactions "
            + "WHERE CAST(id AS VARCHAR) = ?")) {
      for (Object[] row : rows) {
        ps.setString(1, (String) row[1]);
        ps.setString(2, reason);
        ps.setString(3, (String) row[0]);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void exec(Connection connection, String sql) throws Exception {
    try (var stmt = connection.createStatement()) {
      stmt.execute(sql);
    }
  }
}
