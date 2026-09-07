package com.bank.platform.ledger;

import com.bank.platform.common.Brand;
import com.bank.platform.common.Money;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

/**
 * The PDF statement renderer - the byte-level layout engine, in a file of its
 * own so the snapshot assembler never does glyph work.
 *
 * <p>Pure: it reads ONLY the immutable {@link StatementService.Statement} it
 * is handed - no repository, no clock, no transaction - so the rendered
 * document can never combine figures from a different database snapshot than
 * the rows between them (F05). Rendering the same Statement twice produces
 * identical bytes, even after money moves in the database.
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
 * block as ISOLATED letterforms; contiguous RTL runs are pre-reversed into
 * visual order (see {@link #reverseRtlRuns}) so a Persian memo reads
 * right-to-left with every word letter-perfect and the extracted text layer
 * stays faithful, but this renderer does not perform Arabic/Persian
 * contextual joining (shaping) or full bidirectional reordering of
 * mixed-direction lines (PDFBox provides neither) - a documented limit of
 * this simulator, matching the F20 acceptance (StatementUnicodePdfTest).
 * Text extraction and the financial figures are exact.
 */
public final class StatementPdfRenderer {

  /** The embedded broad-coverage font, bundled with its OFL license. */
  private static final String FONT_RESOURCE = "/fonts/DejaVuSans.ttf";

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
      // {date, amount, description, measuredAmountWidth} - date and amount are
      // printed only on the first line of their row. RTL runs are reversed
      // into visual order (isolated letterforms; see reverseRtlRuns).
      List<String[]> display = new ArrayList<>();
      for (StatementService.StatementRow tx : posted) {
        boolean in = account.id().equals(tx.toAccountId());
        String desc = tx.memo() != null ? tx.memo()
            : (in ? "Transfer from " + shortIban(ibans.get(tx.fromAccountId()))
                  : "Transfer to " + shortIban(ibans.get(tx.toAccountId())));
        String date = tx.postedAt().atZone(ZoneOffset.UTC).toLocalDate().toString();
        String amount = (in ? "+" : "-") + Money.plain(tx.amount());
        float amountWidth = textWidth(font, amount, size);
        List<String> lines = wrap(font, size, reverseRtlRuns(desc), descWidth);
        if (lines.isEmpty()) {
          lines.add("");
        }
        for (int i = 0; i < lines.size(); i++) {
          display.add(new String[] {
              i == 0 ? date : "",
              i == 0 ? amount : "",
              lines.get(i),
              i == 0 ? String.valueOf(amountWidth) : ""});
        }
      }

      // Paginate the display lines first - the SAME arithmetic the draw pass
      // uses - so every page's "Page X of N" is the true, final total and no
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
