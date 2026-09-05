package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;
import com.bank.platform.support.ApiTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * A statement longer than one page must still read as a statement: the column
 * heading (Date/Description/Amount) repeats at the top of every page, not just
 * the first, so later pages never show bare rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatementLayoutTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void multiPageStatementRepeatsColumnHeadings() throws Exception {
    String alice = client.register("stmt-layout@example.com", "Stmt Layout");
    String aliceId = client.accountId(alice);

    // Enough deposits to spill past one A4 page of rows (~54 rows/page).
    for (int i = 0; i < 60; i++) {
      client.deposit(alice, aliceId, "1.00");
    }

    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + aliceId + "/statement.pdf")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andReturn();
    byte[] bytes = pdf.getResponse().getContentAsByteArray();
    assertTrue(bytes.length > 500, "PDF should have content");

    try (PDDocument doc = Loader.loadPDF(bytes)) {
      assertTrue(doc.getNumberOfPages() >= 2, "60 rows must produce more than one page");
      String text = new PDFTextStripper().getText(doc);
      // One column-heading line per page - count the literal heading token.
      long headings = occurrences(text, "Description");
      assertEquals(doc.getNumberOfPages(), headings,
          "every page must carry its own Date/Description/Amount heading");
    }
  }

  /**
   * The "Period ... to ..." line must show the SAME inclusive days the window
   * was built from. This is the one place the label and the row query could
   * drift apart again (an exclusive-end mistake renders rows through the last
   * day but labels it as ending a day early), so the rendered dates are
   * pinned here at text level, not just asserted to look like a date.
   */
  @Test
  void datedWindowPrintsItsInclusiveDaysInThePeriodLabel() throws Exception {
    String alice = client.register("stmt-label@example.com", "Stmt Label");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "10.00");

    LocalDate from = LocalDate.now(ZoneOffset.UTC).minusDays(6);
    LocalDate to = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    String text = statementText(alice, aliceId, from, to);

    // The label is the exact inclusive window - neither day shifted by the
    // exclusive end that the row query consumes.
    assertTrue(text.contains("Period " + from + " to " + to),
        "label must print the inclusive days:\n" + text);
    assertFalse(text.contains("Period " + from + " to " + to.plusDays(1)),
        "label must not print the exclusive next day:\n" + text);
  }

  private String statementText(String token, String accountId, LocalDate from, LocalDate to) throws Exception {
    MvcResult pdf = mvc.perform(get("/api/v1/accounts/" + accountId + "/statement.pdf")
            .header("Authorization", "Bearer " + token)
            .param("from", from.toString())
            .param("to", to.toString()))
        .andExpect(status().isOk())
        .andReturn();
    byte[] bytes = pdf.getResponse().getContentAsByteArray();
    assertTrue(bytes.length > 500, "PDF should have content");
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      return new PDFTextStripper().getText(doc);
    }
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
