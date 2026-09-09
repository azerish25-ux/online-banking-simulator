package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Pure layout checks for the PDF description engine,
 * without a Spring context or database: every claim is measured against the
 * REAL embedded font.
 *
 * <ul>
 *   <li>an unbreakable token wider than the column wraps at grapheme-cluster
 *       boundaries into measured lines that each FIT the column, with every
 *       character preserved (no clip, no drop, no mid-cluster break);</li>
 *   <li>Persian/Arabic memos are SHAPED: ICU produces joined presentation
 *       forms, not the old mirrored isolated letters: and Latin content in
 *       the same line stays in order and untouched.</li>
 * </ul>
 */
class StatementPdfTextWrapTest {

  /** The renderer's own A4 description-column width (see StatementPdfRenderer.render). */
  private static final float DESC_WIDTH = 305.3f;

  private static PDType0Font font;

  @BeforeAll
  static void loadFont() throws Exception {
    try (PDDocument doc = new PDDocument();
        InputStream in = StatementPdfRenderer.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
      assertTrue(in != null, "font resource must be bundled");
      font = PDType0Font.load(doc, in, true);
    }
  }

  @Test
  void unbreakableTokenWrapsIntoFittingLinesWithoutLosingCharacters() throws Exception {
    String token = "EEREW-198273645-QWERTYUIOPASDFGHJKLZXCVBNM-"
        + "InternationalBankSettlementReferenceNumber-2026-09";
    // Wide enough that one line cannot hold the token (assert the premise).
    assertTrue(StatementPdfRenderer.toDisplayOrder(token).length() > 0);
    assertTrue(width(token) > DESC_WIDTH, "fixture must genuinely overflow one line");

    List<String> lines = StatementPdfRenderer.wrap(font, 9, token, DESC_WIDTH);
    assertTrue(lines.size() > 1, "the token must wrap onto several lines");
    assertEquals(token, String.join("", lines),
        "wrapping must not add, drop or reorder a single character");

    // The ASCII token is pure LTR: each measured line must FIT the column: // this is the exact defect class that used to overflow into the amounts.
    for (String line : lines) {
      assertTrue(width(line) <= DESC_WIDTH,
          "every wrapped line must fit the column (overflowed at width "
              + width(line) + "): " + line);
    }
  }

  @Test
  void wrapNeverSplitsAGraphemeCluster() throws Exception {
    // Base letters with combining marks (all covered by the embedded DejaVu
    // font): "cafe\u0301" (e + COMBINING ACUTE), "na\u0308ive" (a + DIAERESIS),
    // "a\u030a" (a + RING ABOVE). A grapheme-safe wrap may break between
    // clusters but never inside one, so a combining mark must never lead a
    // wrapped line.
    String cluster = "cafe\u0301 re\u0301sume\u0301 na\u0308ive a\u030a";
    String padded = cluster + " " + "x".repeat(200);
    List<String> lines = StatementPdfRenderer.wrap(font, 9, padded, DESC_WIDTH);
    // A wrap turns the consumed word-boundary space into a LINE BREAK, so the
    // correct reconstruction invariant is whitespace-insensitive: every
    // grapheme must survive, in order, across the wrapped lines.
    assertEquals(compact(padded), compact(String.join("", lines)),
        "every grapheme survives the wrap, in order");
    for (String line : lines) {
      // A combining mark at the very START of a wrapped line would mean the
      // previous line ended inside a cluster.
      assertFalse(line.startsWith("\u0301") || line.startsWith("\u030a") || line.startsWith("\u0308"),
          "no line may begin with a continuation grapheme: " + line);
      assertTrue(width(line) <= DESC_WIDTH, "every line must fit: " + line);
    }
  }

  @Test
  void persianTextIsShapedIntoPresentationFormsNotIsolatedLetters() {
    // "پرداخت به فروشگاه": under real shaping the drawn glyph stream must
    // carry Arabic PRESENTATION FORMS (FB50-FDFF / FE70-FEFF), which is what
    // joins letters into connected script. The old defect mirrored isolated
    // base letters and produced none of these code points.
    String memo = "\u067e\u0631\u062f\u0627\u062e\u062a \u0628\u0647 \u0641\u0631\u0648\u0634\u06af\u0627\u0647";
    String display = StatementPdfRenderer.toDisplayOrder(memo);
    boolean shaped = display.codePoints().anyMatch(cp ->
        (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF));
    assertTrue(shaped, "shaping must emit joined presentation forms, not isolated "
        + "base letters (display codepoints): " + hex(display));
  }

  @Test
  void mixedDirectionLineKeepsLatinIdentifiersAndDigitsInOrder() {
    // LTR prefix + RTL Persian tail + LTR suffix. Reordering must preserve the
    // Latin runs exactly and in order while the Persian run renders RTL.
    String logical = "Ref DE35123456780123456789 "
        + "\u0628\u0631\u0627\u06cc \u0642\u0628\u0636" + " 12.50";
    String display = StatementPdfRenderer.toDisplayOrder(logical);
    assertTrue(display.contains("Ref DE35123456780123456789"),
        "the leading Latin identifier must survive contiguous and in order: " + hex(display));
    assertTrue(display.contains("12.50"),
        "the amount digits must survive contiguous and in order: " + hex(display));
    // The Persian text must be shaped (joined presentation forms present): // the old defect emitted mirrored isolated base letters instead.
    boolean shaped = display.codePoints().anyMatch(cp ->
        (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF));
    assertTrue(shaped, "the RTL portion must be shaped: " + hex(display));
  }

  private static float width(String text) throws Exception {
    return font.getStringWidth(StatementPdfRenderer.toDisplayOrder(text)) / 1000f * 9;
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static String hex(String value) {
    StringBuilder sb = new StringBuilder();
    value.codePoints().forEach(cp -> sb.append(Integer.toHexString(cp)).append(' '));
    return sb.toString();
  }
}
