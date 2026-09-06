package com.bank.platform.ledger;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountNotFoundException;
import com.bank.platform.accounts.AccountService;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.common.Brand;
import com.bank.platform.common.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StatementService {

  private final AccountService accounts;
  private final AccountRepository accountRepository;
  private final TransactionRepository transactions;
  private final Clock clock;
  private final long maxRows;

  public StatementService(
      AccountService accounts,
      AccountRepository accountRepository,
      TransactionRepository transactions,
      Clock clock,
      @Value("${app.statement.max-rows:5000}") long maxRows) {
    this.accounts = accounts;
    this.accountRepository = accountRepository;
    this.transactions = transactions;
    this.clock = clock;
    this.maxRows = maxRows;
  }

  /**
   * One immutable, fully materialized statement (F05). The whole document is
   * built from a SINGLE coherent read: one account read, one row query, one
   * opening-cut aggregate and one IBAN resolution, all inside one
   * REPEATABLE_READ transaction, so every figure and row on the page comes
   * from the same database snapshot. {@code opening + sum(printed rows) =
   * closing} holds BY CONSTRUCTION - the closing is derived from the printed
   * rows - and the opening is the projection balance minus the in-window and
   * post-window net, never today's balance pasted onto a past period.
   *
   * <p>{@code asOf} names the database snapshot the figures came from and
   * {@code version} the statement schema, so re-issuing the same request
   * later produces a visibly NEW document (a later asOf), never a silent
   * mutation of an earlier one. Renderers (PDF/CSV) accept this record and
   * perform no financial queries of their own.
   */
  public record Statement(
      Account account,
      List<Transaction> rows,
      Period period,
      Map<UUID, String> ibans,
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      Instant asOf,
      long version) {

    /** The statement schema version these documents are rendered from. */
    public static final long CURRENT_VERSION = 2;

    public Statement {
      rows = List.copyOf(rows);
      ibans = Map.copyOf(ibans);
    }
  }

  /** A ready-to-stream CSV export: the server-chosen filename and its body. */
  public record CsvStatement(String filename, String content) {}

  @Transactional(readOnly = true)
  public CsvStatement customerCsv(String email, UUID accountId, LocalDate from, LocalDate to) {
    // The CSV and the PDF share ONE snapshot: same row window, same opening
    // and closing figures, same as-of - they cannot drift (F05).
    Statement statement = customerStatement(email, accountId, from, to);
    StringBuilder csv = new StringBuilder("id,posted_at,from_iban,to_iban,amount,currency,memo,status\n");
    for (Transaction tx : statement.rows()) {
      csv.append(tx.getId()).append(',')
          .append(tx.getPostedAt()).append(',')
          .append(cell(tx.getFromAccountId() == null ? "" : statement.ibans().getOrDefault(tx.getFromAccountId(), ""))).append(',')
          .append(cell(tx.getToAccountId() == null ? "" : statement.ibans().getOrDefault(tx.getToAccountId(), ""))).append(',')
          .append(tx.getAmount().toPlainString()).append(',')
          .append(tx.getCurrency()).append(',')
          .append(cell(tx.getMemo() == null ? "" : tx.getMemo())).append(',')
          .append(tx.getStatus().name()).append('\n');
    }
    String filename = "statement-" + statement.account().getIban() + "-"
        + statement.asOf().atZone(ZoneOffset.UTC).toLocalDate() + ".csv";
    return new CsvStatement(filename, csv.toString());
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
    return '\"' + safe.replace("\"", "\"\"") + '\"';
  }

  /**
   * The customer statement: one REPEATABLE_READ transaction builds account,
   * rows, opening/closing and IBAN labels from one database snapshot.
   */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Statement customerStatement(String email, UUID accountId, LocalDate from, LocalDate to) {
    Account account = accounts.accountDetail(email, accountId);
    return build(account, from, to);
  }

  /** Operator-side twin of {@link #customerStatement} (admin authorization is the caller's job). */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Statement adminStatement(UUID accountId, LocalDate from, LocalDate to) {
    Account account = accountRepository.findById(accountId)
        .orElseThrow(() -> new AccountNotFoundException(accountId));
    return build(account, from, to);
  }

  /** The requested window, or the default (today and the 30 days before) when open. */
  private static Period window(LocalDate from, LocalDate to) {
    Period defaults = Period.lastThirtyDays();
    return new Period(from != null ? from : defaults.from(), to != null ? to : defaults.to());
  }

  /** Counterpart-IBAN lookup for rows of one account window (same transaction as the build). */
  public Map<UUID, String> ibanMap(List<Transaction> rows) {
    Set<UUID> ids = rows.stream()
        .flatMap(tx -> Stream.of(tx.getFromAccountId(), tx.getToAccountId()))
        .filter(value -> value != null)
        .collect(Collectors.toSet());
    return accountRepository.findAllById(ids).stream()
        .collect(Collectors.toMap(Account::getId, Account::getIban));
  }

  /**
   * The one place a statement is assembled. All reads happen in the caller's
   * single REPEATABLE_READ transaction; the arithmetic below then derives
   * closing from the printed rows so the identity opening + rows = closing
   * cannot drift, while opening stays a true period-start balance (current
   * projection minus every settled movement at/after the window start - the
   * same +to/-from convention {@link TransactionRepository#sumSettledMovementAfter}
   * owns). A statement for a past period never prints today's balance.
   */
  private Statement build(Account account, LocalDate from, LocalDate to) {
    Period period = window(from, to);
    Instant asOf = clock.instant();
    List<Transaction> rows = rows(account.getId(), period);
    Map<UUID, String> ibans = ibanMap(rows);
    BigDecimal netFromStart = netSettledFrom(account.getId(), period.start());
    BigDecimal opening = account.getBalance().subtract(netFromStart);
    BigDecimal closing = opening;
    for (Transaction tx : rows) {
      // Only POSTED rows reach this list (rows() filters), so every row here
      // moved money: credit the account when it received, debit otherwise.
      boolean credit = account.getId().equals(tx.getToAccountId());
      closing = credit ? closing.add(tx.getAmount()) : closing.subtract(tx.getAmount());
    }
    return new Statement(account, rows, period, ibans, opening, closing, asOf,
        Statement.CURRENT_VERSION);
  }

  private List<Transaction> rows(UUID accountId, Period period) {
    // Guard before loading: a statement for a huge window must not pull every
    // matching row into memory just to render (or to stream out as CSV). The
    // count and the rows share the same settled-in-window definition.
    long matching = transactions.postedCount(accountId, period);
    if (matching > maxRows) {
      throw new TransferValidationException(
          "Statement covers " + matching + " transactions (max " + maxRows
              + "); narrow the date range");
    }
    return transactions.statementRows(accountId, period);
  }

  private BigDecimal netSettledFrom(UUID accountId, Instant from) {
    return transactions.sumSettledMovementAfter(accountId, TxStatus.POSTED, from)
        .orElse(BigDecimal.ZERO);
  }

  /**
   * Renders a one-or-more-page bank statement. Pure: it reads ONLY the
   * immutable {@link Statement} it is handed - no repository, no clock, no
   * transaction - so the rendered document can never combine figures from a
   * different database snapshot than the rows between them (F05). Rendering
   * the same Statement twice produces identical bytes, even after money
   * moves in the database.
   *
   * <p>F20: the body is set in an embedded Unicode TrueType font (DejaVu
   * Sans, bundled under src/main/resources/fonts with its license), so
   * accented names and non-Latin memos are never substituted with '?' or
   * dropped. Columns are positioned from MEASURED string widths - no
   * space-padding in a proportional font - amounts are right-aligned, long
   * descriptions wrap onto continuation lines, the column heading repeats on
   * every page, and each page carries a page number.
   *
   * <p>Honest limits: DejaVu Sans covers Latin/Cyrillic/Greek and the Arabic
   * block as ISOLATED letterforms; this renderer does not perform Arabic/
   * Persian shaping or bidirectional reordering (PDFBox provides neither),
   * so a Persian memo prints its letters in logical order without joining.
   * Text extraction and the financial figures are exact; full bidi/shaping
   * would need a shaping engine and is out of scope for this simulator.
   */
  public byte[] renderPdf(Statement statement) {
    Account account = statement.account();
    List<Transaction> posted = statement.rows();
    Map<UUID, String> ibans = statement.ibans();
    Instant asOf = statement.asOf();

    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream fontIn =
            StatementService.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
      if (fontIn == null) {
        throw new IllegalStateException("Statement font resource is missing");
      }
      PDType0Font font = PDType0Font.load(doc, fontIn, true);

      float margin = 44;
      float width = PDRectangle.A4.getWidth();
      float height = PDRectangle.A4.getHeight();
      float dateCol = margin;
      float amountCol = width - margin - 96;
      float descCol = dateCol + 96;
      float descWidth = amountCol - descCol - 10;
      float size = 9;
      float headSize = 10;
      float mastSize = 16;
      float lineH = 11.5f; // vertical step between wrapped lines of ONE row
      float rowPad = 3.5f;

      String[] header = {
          Brand.PDF_STATEMENT_HEADER,
          "IBAN " + account.getIban() + "  ·  " + account.getType().name() + "  ·  "
              + account.getStatus().name(),
          "Period " + statement.period().from() + " to " + statement.period().to(),
          "Opening " + Money.usd(statement.openingBalance()) + "   ·   Closing "
              + Money.usd(statement.closingBalance()),
          "Statement as of " + asOf + " (UTC) · version " + statement.version()};

      // Pre-lay every row into measured, wrapped lines: date, the description
      // lines it needs, and the right-aligned amount text.
      List<String[]> raw = new ArrayList<>();
      for (Transaction tx : posted) {
        boolean in = account.getId().equals(tx.getToAccountId());
        String desc = tx.getMemo() != null ? tx.getMemo()
            : (in ? "Transfer from " + shortIban(ibans.get(tx.getFromAccountId()))
                  : "Transfer to " + shortIban(ibans.get(tx.getToAccountId())));
        // RTL runs (Arabic block) are reversed into visual order: the font
        // prints isolated letterforms and PDFBox performs no shaping, so the
        // glyph stream must carry the right-to-left sequence to READ correctly
        // and extract back to the exact original memo (F20).
        raw.add(new String[] {
            tx.getPostedAt().atZone(ZoneOffset.UTC).toLocalDate().toString(),
            reverseRtlRuns(desc),
            (in ? "+" : "-") + Money.plain(tx.getAmount())});
      }
      List<String[]> rows = new ArrayList<>();
      for (String[] data : raw) {
        String date = data[0];
        String desc = data[1];
        String amount = data[2];
        List<String> lines = wrap(font, size, desc, descWidth);
        if (lines.isEmpty()) {
          lines.add("");
        }
        for (int i = 0; i < lines.size(); i++) {
          rows.add(new String[] {i == 0 ? date : "", i == 0 ? amount : "", lines.get(i),
              i == 0 ? String.valueOf(textWidth(font, amount, size)) : ""});
        }
      }

      int pageNo = 1;
      int rowIndex = 0;
      float footerY = 40;
      do {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(doc, page);
        float y = height - margin;
        if (pageNo == 1) {
          for (int h = 0; h < header.length; h++) {
            textLine(cs, font, h == 0 ? mastSize : headSize, margin, y, header[h]);
            y -= h == 0 ? 24 : 15;
          }
          y -= 6;
        }
        // Column heading repeats on EVERY page (multi-page context).
        textLine(cs, font, headSize, dateCol, y, "Date");
        textLine(cs, font, headSize, descCol, y, "Description");
        textLine(cs, font, headSize, amountCol - 4, y, "Amount");
        y -= 14;
        while (rowIndex < rows.size() && y >= footerY + 18) {
          String[] line = rows.get(rowIndex);
          float lineHeight = 12;
          if (y - lineHeight < footerY + 12) {
            break; // this row line does not fit - spill to the next page
          }
          if (!line[0].isEmpty()) {
            textLine(cs, font, size, dateCol, y, line[0]);
            // Right-aligned amount against the measured column edge.
            float aw = Float.parseFloat(line[3]);
            textLine(cs, font, size, amountCol - aw, y, line[1]);
          }
          textLine(cs, font, size, descCol, y, line[2]);
          y -= lineHeight;
          rowIndex++;
        }
        textLine(cs, font, 8, margin, footerY, "Page " + pageNo + " of "
            + (rows.isEmpty() ? "1" : "..."));
        cs.close();
        pageNo++;
      } while (rowIndex < rows.size());
      doc.save(out);
      return out.toByteArray();
    } catch (IOException ex) {
      throw new IllegalStateException("Could not render statement PDF", ex);
    }
  }

  private static float textWidth(PDType0Font font, String text, float size) throws IOException {
    return font.getStringWidth(text) / 1000f * size;
  }

  /**
   * Wraps on word boundaries to fit {@code maxWidth} points (measured), and
   * never silently truncates a name or description - anything too long to
   * fit one line continues on the next. A single unbreakable token wider
   * than the column is emitted whole (never chopped mid-word).
   */
  private static List<String> wrap(PDType0Font font, float size, String text, float maxWidth)
      throws IOException {
    List<String> out = new ArrayList<>();
    String[] words = text.split(" ");
    StringBuilder current = new StringBuilder();
    for (String word : words) {
      String trial = current.isEmpty() ? word : current + " " + word;
      if (textWidth(font, trial, size) > maxWidth && !current.isEmpty()) {
        out.add(current.toString());
        current.setLength(0);
        current.append(word);
      } else {
        current.setLength(0);
        current.append(trial);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString());
    }
    return out;
  }

  /**
   * Reverses each contiguous right-to-left (Arabic block) run - characters AND
   * word order - into visual order. The embedded font prints ISOLATED
   * letterforms (no joining), so for a Persian memo to read right-to-left the
   * glyph stream must carry the run in reverse; text extraction then reflects
   * that RTL order with every word's letters exact. LTR content between runs
   * (IBANs, Western digits, punctuation) keeps its order. True bidi/shaping
   * would need a shaping engine - a documented limit.
   */
  private static String reverseRtlRuns(String value) {
    if (value == null) {
      return "";
    }
    int[] points = value.codePoints().toArray();
    StringBuilder out = new StringBuilder(value.length());
    int i = 0;
    while (i < points.length) {
      int start = i;
      boolean rtl = isRtl(points[i]);
      while (i < points.length && isRtl(points[i]) == rtl) {
        i++;
      }
      if (rtl) {
        for (int j = i - 1; j >= start; j--) {
          out.appendCodePoint(points[j]);
        }
      } else {
        for (int j = start; j < i; j++) {
          out.appendCodePoint(points[j]);
        }
      }
    }
    return out.toString();
  }

  /** Arabic block, Syriac/Thaana/other RTL scripts, and Arabic presentation forms. */
  private static boolean isRtl(int cp) {
    return (cp >= 0x0590 && cp <= 0x08FF)
        || (cp >= 0xFB1D && cp <= 0xFDFF)
        || (cp >= 0xFE70 && cp <= 0xFEFF);
  }

  private static String shortIban(String iban) {
    return iban == null ? "external" : "..." + iban.substring(Math.max(0, iban.length() - 6));
  }

  /** One text line in its own text object at an absolute position. */
  private static void textLine(PDPageContentStream cs, PDType0Font font, float size,
      float x, float y, String text) throws IOException {
    cs.beginText();
    cs.setFont(font, size);
    cs.newLineAtOffset(x, y);
    cs.showText(text);
    cs.endText();
  }
}
