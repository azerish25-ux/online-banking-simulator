package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.ledger.StatementService.Statement;
import com.bank.platform.ledger.StatementService.StatementAccount;
import com.bank.platform.ledger.StatementService.StatementRow;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/**
 * Regression: a statement row whose counterpart leg is NULL (a deposit
 * reversal returning to the funding rail, V29; an engine interest row
 * settling against its counteraccount - a shape shipped since V23) must
 * render through BOTH exporters. The PDF description fallback used to probe
 * the immutable IBAN map ({@code Map.copyOf}) with the null leg as the key,
 * which throws NPE on {@code get(null)} → HTTP 500; the CSV renderer already
 * printed the null leg as an empty cell. These renderers are pure, so the
 * statement is built here by hand with memo-less null-leg rows that take the
 * exact crash path (real rows from the engine carry an interest memo, but a
 * reversal never does).
 */
class StatementNullLegRendererTest {

  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final String IBAN = "DE35123456780123456789";

  private final StatementPdfRenderer pdf = new StatementPdfRenderer();
  private final StatementCsvRenderer csv = new StatementCsvRenderer();

  @Test
  void reversalAndInterestNullLegsRenderCsvAndPdfWithoutError() throws Exception {
    Instant posted = Instant.parse("2026-06-20T10:00:00Z");
    BigDecimal deposit = new BigDecimal("500.0000");
    BigDecimal reversal = new BigDecimal("500.0000");
    BigDecimal savingsInterest = new BigDecimal("1.2055");
    BigDecimal loanInterest = new BigDecimal("4.8986");
    Statement statement = new Statement(
        new StatementAccount(ACCOUNT, IBAN, "SAVINGS", "ACTIVE"),
        List.of(
            // Inbound from the rail (deposit-shaped): NULL from leg.
            row(deposit, null, ACCOUNT),
            // Outbound back to the rail (deposit reversal-shaped): NULL to leg.
            row(reversal, ACCOUNT, null),
            // Inbound engine credit (savings interest-shaped): NULL from leg.
            row(savingsInterest, null, ACCOUNT),
            // Outbound engine charge (loan interest-shaped): NULL to leg.
            row(loanInterest, ACCOUNT, null)),
        new Period(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
        Map.of(ACCOUNT, IBAN),
        BigDecimal.ZERO,
        deposit.add(savingsInterest).subtract(reversal).subtract(loanInterest),
        posted,
        Statement.CURRENT_VERSION);

    // PDF must not throw, and the fallback must label the rail leg instead of
    // crashing on the null map key.
    byte[] bytes = pdf.render(statement);
    String text = compact(pdfText(bytes));
    assertEquals(4, occurrences(text, "thefundingrail"),
        "every memo-less null leg renders the engine-side label:\n" + text);
    assertTrue(text.contains("Transferfromthefundingrail"), text);
    assertTrue(text.contains("Transfertothefundingrail"), text);

    // CSV must not throw and must print the null leg as an empty cell while
    // the real leg resolves to the account IBAN. The fixture rows carry no
    // embedded commas (memo is empty), so the 8 CSV columns split cleanly.
    String content = csv.render(statement).content();
    String[] lines = content.split("\\R");
    assertEquals(5, lines.length, "header + 4 rows:\n" + content);
    assertEquals("id,posted_at,from_iban,to_iban,amount,currency,memo,status", lines[0]);
    int inbound = 0;
    int outbound = 0;
    for (int i = 1; i < lines.length; i++) {
      String[] fields = lines[i].split(",");
      assertEquals(8, fields.length, "one field per CSV column:\n" + lines[i]);
      // The from/to IBAN cells are always quoted (cell()): strip for compare.
      String fromIban = fields[2].replace("\"", "");
      String toIban = fields[3].replace("\"", "");
      if (fromIban.isEmpty() && toIban.equals(IBAN)) {
        inbound++;
      } else if (fromIban.equals(IBAN) && toIban.isEmpty()) {
        outbound++;
      }
    }
    assertEquals(2, inbound, "inbound rows keep from_iban empty and resolve to_iban:\n" + content);
    assertEquals(2, outbound, "outbound rows resolve from_iban and keep to_iban empty:\n" + content);
  }

  private static StatementRow row(BigDecimal amount, UUID from, UUID to) {
    return new StatementRow(
        UUID.randomUUID(), Instant.parse("2026-06-20T10:00:00Z"), from, to,
        amount, "USD", null, TxStatus.POSTED);
  }

  private static String pdfText(byte[] bytes) throws Exception {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while ((from = haystack.indexOf(needle, from)) != -1) {
      count++;
      from += needle.length();
    }
    return count;
  }
}
