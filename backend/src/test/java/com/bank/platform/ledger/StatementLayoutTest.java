package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
