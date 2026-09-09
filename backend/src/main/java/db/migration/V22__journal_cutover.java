package db.migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * reconciled cutover. Accounts that hold a pre-journal balance when this
 * migration runs cannot have their history re-derived with certainty (the
 * legacy rows carry no reliable audit trail for every movement), so each gets
 * ONE explicitly labelled OPENING_BALANCE entry that reproduces its current
 * balance against the MIGRATION_OPENING counteraccount. The entry memo keeps
 * provenance (cutover timestamp, the migration that created it). From here
 * on, every movement is journaled live and customer balances reconcile to
 * their journal lines.
 *
 * <p>Nothing is fabricated: no historical posting is invented, no balance is
 * double-counted (reconstructed history + opening), and every original row
 * and identifier is preserved untouched. Zero-balance accounts need no
 * opening entry: their projection already equals an empty journal.
 */
public class V22__journal_cutover extends BaseJavaMigration {

  @Override
  public void migrate(Context context) throws Exception {
    // NOTE: never close context.getConnection(): Flyway owns it and will commit/rollback.
    Connection connection = context.getConnection();
    Instant cutover = Instant.now();
    // setObject with a bare Instant cannot infer a SQL type on PostgreSQL;
    // H2 accepts it, the PG driver requires an explicit java.sql type.
    java.sql.Timestamp cutoverTs = java.sql.Timestamp.from(cutover);
    String memo = "Cutover opening balance " + cutover + " (V22 reconciled journal)";
    try (PreparedStatement select = connection.prepareStatement(
        "SELECT id, balance FROM accounts WHERE balance <> 0 ORDER BY id");
        PreparedStatement insertEntry = connection.prepareStatement(
            "INSERT INTO journal_entries (id, kind, operation_ref, currency, posted_at, memo) "
                + "VALUES (?, 'OPENING_BALANCE', ?, 'USD', ?, ?)");
        PreparedStatement insertLine = connection.prepareStatement(
            "INSERT INTO journal_lines "
                + "(id, entry_id, account_id, counteraccount, amount, ordinal, posted_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      ResultSet rows = select.executeQuery();
      while (rows.next()) {
        UUID accountId = UUID.fromString(rows.getString(1));
        java.math.BigDecimal balance = rows.getBigDecimal(2);
        UUID entryId = UUID.randomUUID();
        insertEntry.setObject(1, entryId);
        insertEntry.setObject(2, accountId.toString());
        insertEntry.setObject(3, cutoverTs);
        insertEntry.setString(4, memo);
        insertEntry.executeUpdate();
        // Customer line reproduces the balance exactly; the migration side
        // mirrors it so the pair nets to zero.
        insertLine.setObject(1, UUID.randomUUID());
        insertLine.setObject(2, entryId);
        insertLine.setObject(3, accountId);
        insertLine.setObject(4, null);
        insertLine.setBigDecimal(5, balance);
        insertLine.setInt(6, 0);
        insertLine.setObject(7, cutoverTs);
        insertLine.executeUpdate();
        insertLine.setObject(1, UUID.randomUUID());
        insertLine.setObject(2, entryId);
        insertLine.setObject(3, null);
        insertLine.setString(4, "MIGRATION_OPENING");
        insertLine.setBigDecimal(5, balance.negate());
        insertLine.setInt(6, 1);
        insertLine.setObject(7, cutoverTs);
        insertLine.executeUpdate();
      }
    }
  }
}
