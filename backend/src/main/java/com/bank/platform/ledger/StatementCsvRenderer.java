package com.bank.platform.ledger;

import java.time.ZoneOffset;

/**
 * The CSV statement renderer: serialization in a file of its own so the
 * snapshot assembler never does byte-level escaping.
 *
 * <p>Pure: it reads ONLY the immutable {@link StatementService.Statement} it
 * is handed: no repository, no clock, no transaction: so the exported
 * rows can never come from a different database snapshot than the figures
 * beside them. The caller (StatementService) owns the repeatable-read
 * boundary that builds the Statement; this class only turns immutable value
 * rows into escaped, ready-to-stream CSV.
 */
public final class StatementCsvRenderer {

  /** Column order is the exported contract; the row values follow verbatim. */
  private static final String HEADER = "id,posted_at,from_iban,to_iban,amount,currency,memo,status\n";

  /** Renders the statement to a ready-to-stream export (filename + body). */
  public StatementService.CsvStatement render(StatementService.Statement statement) {
    StringBuilder csv = new StringBuilder(HEADER);
    for (StatementService.StatementRow row : statement.rows()) {
      csv.append(row.id()).append(',')
          .append(row.postedAt()).append(',')
          .append(cell(row.fromAccountId() == null ? "" : statement.ibans().getOrDefault(row.fromAccountId(), ""))).append(',')
          .append(cell(row.toAccountId() == null ? "" : statement.ibans().getOrDefault(row.toAccountId(), ""))).append(',')
          .append(row.amount().toPlainString()).append(',')
          .append(row.currency()).append(',')
          .append(cell(row.memo() == null ? "" : row.memo())).append(',')
          .append(row.status().name()).append('\n');
    }
    String filename = "statement-" + statement.account().iban() + "-"
        + statement.asOf().atZone(ZoneOffset.UTC).toLocalDate() + ".csv";
    return new StatementService.CsvStatement(filename, csv.toString());
  }

  /**
   * CSV-escapes one cell. Cells are always quoted; user-controlled values that
   * start with a spreadsheet formula character (= + - @ or a tab/CR) are
   * prefixed with a single quote so opening the export in Excel/Sheets cannot
   * execute a formula smuggled through a memo.
   */
  private static String cell(String value) {
    String safe = value == null ? "" : value;
    if (!safe.isEmpty()) {
      char first = safe.charAt(0);
      if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
        safe = "'" + safe;
      }
    }
    return '"' + safe.replace("\"", "\"\"") + '"';
  }
}
