package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V28: consult INTEREST audit provenance INDEPENDENTLY of the current kind
 * label (a forward correction of V24's blind spot).
 *
 * <p>V24 only looked for the {@code INTEREST_POSTED} audit trail on rows that
 * were ALREADY labelled {@code INTEREST} (by V8's memo guessing). A genuine
 * engine posting whose memo did not match the substring therefore fell into
 * V24's structural bucket: a savings-interest credit (from-null, to-only) with
 * an audit trail was classified as a DEPOSIT, and a loan-interest charge
 * (from-only) was left labelled TRANSFER. Both are audit-proven engine
 * interest that V24 actively mislabelled because the provenance check ran
 * after, and only inside, the memo-derived label.
 *
 * <p>This migration inspects the audit trail FIRST and independently:
 *
 * <ol>
 *   <li><b>One-sided rows with an {@code INTEREST_POSTED} audit entry are
 *       engine interest</b> - the engine only ever writes one-sided postings
 *       (savings credits are to-only, loan charges are from-only) and it
 *       audited every posting by transaction id. Whatever structural label
 *       (TRANSFER or DEPOSIT) an earlier migration guessed, the audit is the
 *       stronger evidence: the row becomes {@code kind = 'INTEREST'},
 *       {@code kind_evidence = 'AUDIT'}.</li>
 *   <li><b>Two-sided rows carrying an {@code INTEREST_POSTED} audit are
 *       contradictory evidence</b> (the engine never writes two sides): they
 *       are quarantined as {@code kind_evidence = 'UNCERTAIN'} - never
 *       silently re-tagged to whichever classification is easier to process.</li>
 * </ol>
 *
 * <p>Every decision is appended to {@code transaction_kind_review} (V24's
 * archive keeps its original decision; this migration adds the later,
 * audit-backed one, preserving the audit history). Already-AUDIT and
 * already-UNCERTAIN rows are never touched. No balance, amount, memo or id is
 * changed. The migration is idempotent: on a fresh database there are no rows
 * to revisit, and re-running (Flyway never does, but defence in depth) finds
 * nothing left to correct.
 */
public class V28__kind_audit_provenance_first extends BaseJavaMigration {

  private static final String AUDIT_EVIDENCE =
      "EXISTS (SELECT 1 FROM audit_logs a "
          + "WHERE a.action = 'INTEREST_POSTED' AND a.entity = 'Transaction' "
          + "AND a.entity_id = CAST(t.id AS VARCHAR))";

  @Override
  public void migrate(Context context) throws Exception {
    Connection connection = context.getConnection();

    // 1) Audit-proven engine postings the current label got wrong. One-sided
    //    rows whose audit says the interest engine posted them - whatever the
    //    current kind/evidence claims.
    List<Object[]> promote = select(connection,
        "SELECT t.id, t.kind FROM transactions t "
            + "WHERE t.kind_evidence <> 'AUDIT' AND t.kind_evidence <> 'UNCERTAIN' "
            + "AND ((t.from_account_id IS NULL) <> (t.to_account_id IS NULL)) "
            + "AND " + AUDIT_EVIDENCE);

    // 2) Contradictions: an INTEREST audit on a row the engine could never
    //    have written (two sides). Quarantine, never guess. AUDIT-evidenced
    //    rows are included: V24 itself could leave a two-sided row labelled
    //    INTEREST/AUDIT (the memo classifier ran before the structural rule),
    //    and that contradiction is exactly what must surface for an operator.
    List<Object[]> contradict = select(connection,
        "SELECT t.id, t.kind FROM transactions t "
            + "WHERE t.kind_evidence <> 'UNCERTAIN' "
            + "AND t.from_account_id IS NOT NULL AND t.to_account_id IS NOT NULL "
            + "AND " + AUDIT_EVIDENCE);

    for (Object[] row : promote) {
      archive(connection, row, "V28: INTEREST_POSTED audit overrides the current "
          + row[1] + " label; corrected to INTEREST");
      exec(connection, "UPDATE transactions t SET kind = 'INTEREST', kind_evidence = 'AUDIT' "
          + "WHERE t.kind_evidence <> 'AUDIT' AND CAST(t.id AS VARCHAR) = '"
          + escape(String.valueOf(row[0])) + "'");
    }
    for (Object[] row : contradict) {
      archive(connection, row,
          "V28: INTEREST_POSTED audit on a two-sided row - contradictory evidence, quarantined");
      exec(connection, "UPDATE transactions t SET kind_evidence = 'UNCERTAIN' "
          + "WHERE t.kind_evidence <> 'UNCERTAIN' AND CAST(t.id AS VARCHAR) = '"
          + escape(String.valueOf(row[0])) + "'");
    }
  }

  /** Inserts a new archive decision, or appends to V24's existing one. */
  private void archive(Connection connection, Object[] row, String reason) throws Exception {
    // H2 (PostgreSQL mode) has no ON CONFLICT for INSERT..SELECT, so the
    // insert is guarded with NOT EXISTS - the review row is keyed to the
    // transaction, so at most one insert can ever land.
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO transaction_kind_review (transaction_id, prior_kind, reason, memo_snippet) "
            + "SELECT CAST(id AS UUID), ?, ?, SUBSTRING(memo, 1, 140) FROM transactions t "
            + "WHERE CAST(t.id AS VARCHAR) = ? AND NOT EXISTS "
            + "(SELECT 1 FROM transaction_kind_review r WHERE r.transaction_id = t.id)")) {
      ps.setString(1, (String) row[1]);
      ps.setString(2, reason);
      ps.setString(3, String.valueOf(row[0]));
      ps.executeUpdate();
    }
    // V24 may already hold a decision for this transaction (e.g. its structural
    // DEPOSIT guess). Append the audit-backed correction so the audit history
    // shows BOTH opinions - never erase the first.
    try (PreparedStatement ps = connection.prepareStatement(
        "UPDATE transaction_kind_review SET reason = reason || '; ' || ? "
            + "WHERE CAST(transaction_id AS VARCHAR) = ? AND reason NOT LIKE ?")) {
      ps.setString(1, reason);
      ps.setString(2, String.valueOf(row[0]));
      ps.setString(3, "%" + reason + "%");
      ps.executeUpdate();
    }
  }

  private List<Object[]> select(Connection connection, String sql) throws Exception {
    List<Object[]> rows = new ArrayList<>();
    try (PreparedStatement ps = connection.prepareStatement(sql);
        ResultSet rs = ps.executeQuery()) {
      while (rs.next()) {
        rows.add(new Object[] {rs.getString(1), rs.getString(2)});
      }
    }
    return rows;
  }

  private void exec(Connection connection, String sql) throws Exception {
    try (var stmt = connection.createStatement()) {
      stmt.execute(sql);
    }
  }

  private static String escape(String value) {
    return value.replace("'", "''");
  }
}
