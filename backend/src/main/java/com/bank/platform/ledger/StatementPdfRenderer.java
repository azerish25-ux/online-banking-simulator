package com.bank.platform.ledger;

import com.bank.platform.common.Brand;
import com.bank.platform.common.Money;
import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.Bidi;
import java.text.BreakIterator;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The PDF statement renderer: the byte-level layout engine, in a file of its
 * own so the snapshot assembler never does glyph work.
 *
 * <p>Pure: it reads ONLY the immutable {@link StatementService.Statement} it
 * is handed: no repository, no clock, no transaction: so the rendered
 * document can never combine figures from a different database snapshot than
 * the rows between them. Rendering the same Statement twice produces
 * identical bytes, even after money moves in the database.
 *
* <p>the body is set in an embedded Unicode TrueType font (DejaVu
 * Sans, bundled under src/main/resources/fonts with its license), so
 * accented names and non-Latin memos are never substituted with '?' or
 * dropped. Columns are positioned from MEASURED string widths: no
 * space-padding in a proportional font: amounts are right-aligned, long
 * descriptions wrap onto continuation lines, the column heading repeats on
 * every page, and each page carries a page number.
 *
 * <p>RTL text is shaped and ordered with the ICU4J
 * ArabicShaping + {@link Bidi} pipeline (UAX #9/#11): Arabic/Persian
 * letters become joined presentation forms in correct visual order, with
 * mixed-direction lines (identifiers, digits, punctuation) resolved by
 * reordered runs. Oversized unbroken tokens are wrapped at grapheme-cluster
 * boundaries so no content is dropped or clipped against the amount column.
 */
public final class StatementPdfRenderer {

  /** The embedded broad-coverage font, bundled with its OFL license. */
  private static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";

  /**
   * ICU ArabicShaping (UAX #11): joins Arabic/Persian letters into contextual
   * presentation forms. Reused across render calls: the shaper is stateless.
   */
  private static final ArabicShaping ARABIC_SHAPER =
      new ArabicShaping(ArabicShaping.LETTERS_SHAPE);

  /** Renders the statement to PDF bytes. */
  public byte[] render(StatementService.Statement statement) {
    StatementService.StatementAccount account = statement.account();
    List<StatementService.StatementRow> posted = statement.rows();
    Map<UUID, String> ibans = statement.ibans();
    Instant asOf = statement.asOf();

    try (PDDocument doc = new PDDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream fontIn = StatementPdfRenderer.class.getResourceAsStream(FONT_RESOURCE)) {
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
      float lineHeight = 12;
      float footerY = 40;
      // Page one carries the masthead block above the column heading; every
      // later page starts directly at the heading.
      float firstPageHeaderHeight = 24 + 15f * 4 + 6 + 14;
      float otherPageHeaderHeight = 14;

      String[] header = {
          Brand.PDF_STATEMENT_HEADER,
          "IBAN " + account.iban() + "  ·  " + account.type() + "  ·  "
              + account.status(),
          "Period " + statement.period().from() + " to " + statement.period().to(),
          "Opening " + Money.usd(statement.openingBalance()) + "   ·   Closing "
              + Money.usd(statement.closingBalance()),
          "Statement as of " + asOf + " (UTC) · version " + statement.version()};

      // Pre-lay every row into measured, wrapped display lines. A line carries
      // {date, amount, description, measuredAmountWidth}: date and amount are
      // printed only on the first line of their row. Logical text is wrapped
      // first; each wrapped line is then shaped + reordered into display order
      // (toDisplayOrder) right before it is drawn.
      List<String[]> display = new ArrayList<>();
      for (StatementService.StatementRow tx : posted) {
        boolean in = account.id().equals(tx.toAccountId());
        String desc = tx.memo() != null ? tx.memo()
            : (in ? "Transfer from " + counterpartLabel(ibans, tx.fromAccountId())
                  : "Transfer to " + counterpartLabel(ibans, tx.toAccountId()));
        String date = tx.postedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
        String amount = (in ? "+" : "-") + Money.plain(tx.amount());
        float amountWidth = textWidth(font, amount, size);
        // Wrap the LOGICAL text (grapheme-safe); each wrapped display line is
        // then shaped+reordered independently, so a break never lands inside
        // a grapheme and never inverts mid-run ordering.
        List<String> lines = wrap(font, size, desc, descWidth);
        if (lines.isEmpty()) {
          lines.add("");
        }
        for (int i = 0; i < lines.size(); i++) {
          display.add(new String[] {
              i == 0 ? date : "",
              i == 0 ? amount : "",
              toDisplayOrder(lines.get(i)),
              i == 0 ? String.valueOf(amountWidth) : ""});
        }
      }

      // Paginate the display lines first: the SAME arithmetic the draw pass
      // uses: so every page's "Page X of N" is the true, final total and no
      // page can silently gain or lose a line between counting and drawing.
      List<List<String[]>> pages = new ArrayList<>();
      {
        int index = 0;
        int pageNo = 1;
        while (index < display.size() || pages.isEmpty()) {
          float bodyTop = height - margin
              - (pageNo == 1 ? firstPageHeaderHeight : otherPageHeaderHeight);
          float y = bodyTop;
          List<String[]> pageLines = new ArrayList<>();
          while (index < display.size()
              && y >= footerY + lineHeight + 8
              && y - lineHeight >= footerY + 10) {
            pageLines.add(display.get(index));
            index++;
            y -= lineHeight;
          }
          pages.add(pageLines);
          pageNo++;
          if (index >= display.size()) {
            break;
          }
        }
      }

      int totalPages = Math.max(pages.size(), 1);
      for (int pageNo = 0; pageNo < pages.size(); pageNo++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream cs = new PDPageContentStream(doc, page);
        float y = height - margin;
        if (pageNo == 0) {
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
        for (String[] line : pages.get(pageNo)) {
          if (!line[0].isEmpty()) {
            textLine(cs, font, size, dateCol, y, line[0]);
            // Right-aligned amount against the measured column edge.
            textLine(cs, font, size, amountCol - Float.parseFloat(line[3]), y, line[1]);
          }
          textLine(cs, font, size, descCol, y, line[2]);
          y -= lineHeight;
        }
        textLine(cs, font, 8, margin, footerY,
            "Page " + (pageNo + 1) + " of " + totalPages
                + (totalPages > 1 ? " · continued" : ""));
        cs.close();
      }
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
   * never silently truncates a name or description: anything too long to
   * fit one line continues on the next. Widths are measured on the line's
   * DISPLAY order (the string the pen draws), so RTL shaping never makes a
   * wrapped line overflow the amount column.
   *
   * A single unbreakable token wider than the column is wrapped at
   * GRAPHEME-CLUSTER boundaries (BreakIterator): content is never dropped and
   * a cluster (a base letter with its combining marks, an emoji with its
   * variation selectors/ZWJ sequence) is never split mid-glyph.
   */
  static List<String> wrap(PDType0Font font, float size, String text, float maxWidth)
      throws IOException {
    List<String> out = new ArrayList<>();
    String[] words = text.split(" ");
    StringBuilder current = new StringBuilder();
    for (String word : words) {
      // A word that would overflow the CURRENT line ends that line first: // the word itself starts the next one, never a space-straddling
      // fragment.
      if (!current.isEmpty()
          && displayWidth(font, size, current + " " + word) > maxWidth) {
        out.add(current.toString());
        current.setLength(0);
      }
      // A word wider than a WHOLE line cannot fit anywhere: grapheme-wrap it
      // across its own measured lines instead of emitting one overflowing row
      // (the old clip/overlap defect).
      if (displayWidth(font, size, word) > maxWidth) {
        out.addAll(graphemeChunks(font, size, word, maxWidth));
      } else if (current.length() > 0) {
        current.append(' ').append(word);
      } else {
        current.append(word);
      }
    }
    if (!current.isEmpty()) {
      out.add(current.toString());
    }
    return out;
  }

  /**
   * Splits one unbreakable token into the fewest measured chunks that each
   * fit {@code maxWidth}, breaking ONLY between grapheme clusters: never
   * inside a cluster (combining marks stay with their base letter).
   */
  private static List<String> graphemeChunks(PDType0Font font, float size, String token,
      float maxWidth) throws IOException {
    List<String> chunks = new ArrayList<>();
    BreakIterator it = BreakIterator.getCharacterInstance(Locale.ROOT);
    it.setText(token);
    int start = it.first();
    int end = it.next();
    StringBuilder line = new StringBuilder();
    while (end != BreakIterator.DONE) {
      String grapheme = token.substring(start, end);
      if (!line.isEmpty()
          && displayWidth(font, size, line.toString() + grapheme) > maxWidth) {
        chunks.add(line.toString());
        line.setLength(0);
      }
      line.append(grapheme);
      start = end;
      end = it.next();
    }
    if (line.length() > 0) {
      chunks.add(line.toString());
    }
    return chunks.isEmpty() ? List.of(token) : chunks;
  }

  /** The measured width of one logical line in the order the pen draws it. */
  private static float displayWidth(PDType0Font font, float size, String logical)
      throws IOException {
    return textWidth(font, toDisplayOrder(logical), size);
  }

  /**
   * Replace the old character-run reversal with a
   * supported bidirectional + shaping pipeline. Each logical line is (1)
   * SHAPED with ICU ArabicShaping: Arabic/Persian letters become joined
   * presentation forms, so a Persian memo renders as connected script, not
   * isolated letterforms: then (2) REORDERED with ICU {@code Bidi} into the
   * glyph order the pen draws (left to right across the page).
   *
   * The visual map (UAX #9, character by character) directly yields that
   * order: appending the shaped character at each visual position produces
   * the string the pen draws: RTL runs come out reversed so the joining
   * computed for logical neighbors lands on the glyphs that ARE neighbors on
   * the page, LTR runs (IBANs, digits, punctuation) stay in order, and
   * nested runs are handled by the engine. A pure-LTR line is untouched
   * (fast path). PDFBox's own extractor maps the presentation forms back to
   * base letters, so the PDF text layer stays faithful to the original memo.
   */
  static String toDisplayOrder(String logical) {
    if (logical == null || logical.isEmpty()) {
      return logical == null ? "" : logical;
    }
    String shaped;
    try {
      shaped = ARABIC_SHAPER.shape(logical);
    } catch (ArabicShapingException ex) {
      // Never let a shaping anomaly drop the row: render unshaped rather
      // than losing content (the text layer stays faithful either way).
      shaped = logical;
    }
    // java.text.Bidi resolves the UAX #9 levels. Each maximal run of one
    // level is one atomic item that CARRIES its level (reorderVisually
    // permutes the items but not a separate level array, so the level must
    // travel with the text). Reordering places the items in visual order;
    // an RTL run's characters are then emitted in reverse so the joining ICU
    // computed for the logical neighbors lands on the glyphs that ARE
    // neighbors on the page, while LTR runs (IBANs, digits, punctuation)
    // keep their order. Pure-LTR lines take the fast path below.
    record Run(String text, byte level) {}
    Bidi bidi = new Bidi(shaped, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT);
    if (bidi.baseIsLeftToRight() && !bidi.isMixed()) {
      // Pure LTR: nothing to reorder; the fast path also keeps the cost of
      // the level computation off every ordinary English statement row.
      return shaped;
    }
    // One run per resolved level (logical order); every run CARRIES its own
    // level because Bidi.reorderVisually permutes the objects but leaves a
    // separate level array untouched: so the RTL decision must travel with
    // the text, not sit in an array that ends up paired with the wrong run.
    int count = bidi.getRunCount();
    Object[] runs = new Object[count];
    byte[] levels = new byte[count];
    for (int r = 0; r < count; r++) {
      String text = shaped.substring(bidi.getRunStart(r), bidi.getRunLimit(r));
      byte level = (byte) bidi.getRunLevel(r);
      runs[r] = new Run(text, level);
      levels[r] = level;
    }
    Bidi.reorderVisually(levels, 0, runs, 0, count);
    StringBuilder out = new StringBuilder(shaped.length() + 8);
    for (Object item : runs) {
      Run run = (Run) item;
      boolean rtl = (run.level() & 1) == 1;
      out.append(rtl ? new StringBuilder(run.text()).reverse() : run.text());
    }
    return out.toString();
  }

  /**
   * The label for a memo-less row whose counterpart is the ledger's EXTERNAL
   * side: no second account exists to name. Deposits (and their V29
   * reversals) settle against the simulator-funding rail; interest posts
   * against the engine's INTEREST counteraccount. The statement row does not
   * carry the kind, so one engine-side name serves every such leg.
   */
  private static final String ENGINE_SIDE_LABEL = "the funding rail";

  private static String shortIban(String iban) {
    return iban == null ? "external" : "..." + iban.substring(Math.max(0, iban.length() - 6));
  }

  /**
   * The display label for a row's counterpart leg when the row has no memo.
   * A REAL account leg resolves through the statement's IBAN map to a short
   * IBAN. A NULL leg: money arriving from, or returning to, the funding rail
   * (a deposit or its reversal), or an engine interest row settling against
   * its counteraccount: is NAMED instead of looked up: there is no account
   * to label, and the immutable IBAN map must never be probed with a null
   * key (an ImmutableCollections map throws NPE on {@code get(null)}: the
   * defect that 500'd a statement containing a deposit-reversal row). A real
   * leg somehow absent from the map (cannot happen for a statement built
   * from its own rows) still degrades to "external" via {@link #shortIban}.
   */
  private static String counterpartLabel(Map<UUID, String> ibans, UUID leg) {
    return leg == null ? ENGINE_SIDE_LABEL : shortIban(ibans.get(leg));
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
