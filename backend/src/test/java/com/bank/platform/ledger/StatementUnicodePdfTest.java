package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.support.ApiTestClient;
import java.io.InputStream;
import tools.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F20 - the statement PDF must not corrupt names or money descriptions.
 *
 * <p>The renderer embeds a Unicode TrueType font (DejaVu Sans, bundled under
 * src/main/resources/fonts with its license) and places every cell at a
 * measured coordinate, so accented and non-Latin text is preserved as its
 * real code points instead of the old '?' substitution, and long descriptions
 * wrap rather than being space-padded and cut.
 *
 * <p>Coverage is verified against the REAL embedded font (glyph width > 0 for
 * every fixture code point), not just text extraction - extraction can carry
 * a code point whose glyph is missing and prints as a blank box.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatementUnicodePdfTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void statementPdfPreservesAccentedPersianAndLongMemosWithoutSubstitution() throws Exception {
    String alice = client.register("stmt-utf8@example.com", "Alice Müller-Jöhn");
    String bob = client.register("stmt-utf8-b@example.com", "Sofia Sánchez");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    // Fund, then move money with memos that exercise the failure modes: an
    // accented name, a Persian phrase (shaped + bidi-ordered by the ICU
    // pipeline - the PDF's own extractor maps presentation forms back to base
    // letters and reorders to logical text, so the layer stays faithful), and
    // a long spaced memo that must WRAP, not truncate.
    client.deposit(alice, aliceId, "100000.00"); // max deposit amount
    String persianMemo = "پرداخت به فروشگاه تهران برای صورتحساب مهر";
    String accentedMemo = "Rent São Paulo - Café Müller réservé";
    String longMemo =
        "Quarterly settlement for the joint household budget including utilities and rent "
            + "shared across all members of the household fund";
    transfer(alice, bobIban, "4000.00", persianMemo);
    transfer(alice, bobIban, "250.50", accentedMemo);
    transfer(alice, bobIban, "199.99", longMemo);

    byte[] bytes = statementPdf(alice, aliceId);
    String text = text(bytes);

    // No substitution: every original code point survives into the PDF text.
    assertEquals(-1, text.indexOf('?'), "the old '?' substitution must be gone:\n" + text);
    // LTR memos must survive EXACTLY (extraction may fold wrapped lines, so
    // compare with all whitespace removed).
    for (String needle : new String[] {
        "Rent São Paulo - Café Müller réservé",
        "Quarterly settlement for the joint household budget including utilities and rent "
            + "shared across all members of the household fund"}) {
      assertTrue(compact(text).contains(compact(needle)),
          "memo must survive exactly (no truncation or mangling):\n" + text);
    }
    // Persian renders shaped and bidi-ordered; PDFBox's extractor unshapes
    // the presentation forms and restores logical order, so the WHOLE memo
    // must come back letter-perfect in its original logical sequence.
    assertTrue(compact(text).contains(compact(persianMemo)),
        "the Persian memo must survive exactly, in logical order:\n" + text);
    // The account masthead carries the accented account-holder context too.
    assertTrue(text.contains("Müller") || text.contains("Müller-Jöhn"), text);
    // Financial values on the same lines as RTL memos stay exact.
    assertTrue(text.contains("-4000.00") || text.contains("4000.00"), text);

    // Multi-page heading repetition is proven by StatementLayoutTest (60 rows);
    // here the wrapped row must fit one coherent page with its column heading.
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      assertEquals(1, doc.getNumberOfPages());
      assertEquals(1, occurrences(text, "Description"), "one page carries one heading");
    }
  }

  @Test
  void longUnbrokenTokenWrapsAtGraphemeBoundariesWithoutDroppingContent() throws Exception {
    String alice = client.register("stmt-token@example.com", "Long Token");
    String bob = client.register("stmt-token-b@example.com", "Token B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "5000.00");

    // One UNBROKEN token far wider than the description column (no spaces to
    // wrap at): the renderer must split it at grapheme boundaries and print
    // every character - a single overflowed line would clip it. Mixing a
    // Persian token exercises shaping + grapheme wrap together.
    String asciiToken = "EEREW-198273645-QWERTYUIOPASDFGHJKLZXCVBNM-"
        + "InternationalBankSettlementReferenceNumber-2026-09";
    String persianToken = "پرداخت-نهایی-سپرده-گذاری-بلندمدت-صورتحساب";
    transfer(alice, bobIban, "1200.00", asciiToken);
    transfer(alice, bobIban, "1300.00", persianToken);

    byte[] bytes = statementPdf(alice, aliceId);
    String text = text(bytes);
    // No character of either token may be dropped or mangled by the wrap.
    assertTrue(compact(text).contains(asciiToken),
        "the whole unbroken token must survive:\n" + text);
    assertTrue(compact(text).contains(compact(persianToken)),
        "the whole unbroken Persian token must survive, shaped:\n" + text);
    assertEquals(-1, text.indexOf('?'), "no substitution glyphs:\n" + text);
    // The renderer itself never clips: StatementPdfTextWrapTest proves each
    // wrapped line is MEASURED to fit the description column (grapheme-safe),
    // so an overflowing row cannot be hiding behind a complete text layer.
  }

  @Test
  void theEmbeddedFontReallyCoversEveryFixtureCodePoint() throws Exception {
    // Load the same resource the renderer uses and assert real glyph widths:
    // a width of zero means the code point would print as a blank/notdef box.
    try (PDDocument doc = new PDDocument();
        InputStream in = StatementService.class.getResourceAsStream("/fonts/DejaVuSans.ttf")) {
      assertTrue(in != null, "font resource must be bundled");
      PDType0Font font = PDType0Font.load(doc, in, true);
      String fixture = "Alice Müller-Jöhn São Paulo réservé "
          + "پرداخت به فروشگاه تهران برای صورتحساب مهر € ...";
      for (int codePoint : fixture.codePoints().toArray()) {
        if (Character.isWhitespace(codePoint)) {
          continue;
        }
        String ch = new String(Character.toChars(codePoint));
        assertTrue(font.getStringWidth(ch) > 0,
            "embedded font must carry a real glyph for U+" + Integer.toHexString(codePoint));
      }
    }
  }

  @Test
  void emptyPeriodStillProducesAOnePageStatement() throws Exception {
    String alice = client.register("stmt-utf8-e@example.com", "Empty Period");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "5.00");

    // A valid future window with no rows: must render one masthead page, not 500.
    byte[] bytes = statementPdf(alice, aliceId, "2099-01-01", "2099-01-31");
    String text = text(bytes);
    assertTrue(text.contains("Period 2099-01-01 to 2099-01-31"), text);
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      assertEquals(1, doc.getNumberOfPages());
    }
    // The opening/closing figures legitimately carry the $5.00 balance; the
    // deposit must not appear as a ROW (amount cells print with a + sign).
    assertFalse(text.contains("+5.00"), "no rows may leak into an empty window:\n" + text);
  }

  private void transfer(String token, String toIban, String amount, String memo) throws Exception {
    var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
        .post("/api/v1/transfers")
        .header("Authorization", "Bearer " + token)
        .header("Idempotency-Key", "tx-" + java.util.UUID.randomUUID())
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .content("{\"toIban\":\"%s\",\"amount\":\"%s\",\"memo\":\"%s\"}".formatted(
            toIban, amount, memo.replace("\"", "\\\"")));
    MvcResult result = mvc.perform(request).andReturn();
    assertTrue(result.getResponse().getStatus() == 201 || result.getResponse().getStatus() == 200,
        "transfer failed: " + result.getResponse().getStatus()
            + " " + result.getResponse().getContentAsString());
  }

  private byte[] statementPdf(String token, String accountId) throws Exception {
    return statementPdf(token, accountId, null, null);
  }

  private byte[] statementPdf(String token, String accountId, String from, String to)
      throws Exception {
    var get = get("/api/v1/accounts/" + accountId + "/statement.pdf")
        .header("Authorization", "Bearer " + token);
    if (from != null) {
      get = get.param("from", from).param("to", to);
    }
    MvcResult pdf = mvc.perform(get).andExpect(status().isOk()).andReturn();
    return pdf.getResponse().getContentAsByteArray();
  }

  private static String text(byte[] bytes) throws Exception {
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private static String compact(String value) {
    return value.replaceAll("\\s+", "");
  }

  private static long occurrences(String haystack, String needle) {
    long count = 0;
    int from = 0;
    while ((from = haystack.indexOf(needle, from)) != -1) {
      count++;
      from += needle.length();
    }
    return count;
  }
}
