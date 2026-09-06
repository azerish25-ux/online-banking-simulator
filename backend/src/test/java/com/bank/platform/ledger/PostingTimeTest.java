package com.bank.platform.ledger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * F04: request time and posting time are separate facts. A review-threshold
 * transfer is REQUESTED when its HELD row is created and only POSTS when an
 * operator approves it - across a month boundary on purpose here. Statements,
 * monthly summaries and daily totals must cut on the posting time (money in
 * the month it moved), while the interactive history keeps the request time.
 * The test drives the business clock (TimeConfig bean replaced by a settable
 * clock) so no wall-clock midnight is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostingTimeTest {

  /** Shared mutable clock handed to every bean in this context. */
  private static final SettableClock CLOCK = new SettableClock();

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;

  ApiTestClient client;

  @BeforeEach
  void freezeClockBeforeMonthEnd() {
    CLOCK.set(Instant.parse("2026-05-31T22:00:00Z"));
    client = new ApiTestClient(mvc, json);
  }

  @Test
  void heldApprovalAcrossMonthPostsIntoSettlementMonth() throws Exception {
    // End of May: Alice funds 20,000 and requests a 12,000 wire (>= threshold,
    // so it is HELD - created May 31, nothing has moved).
    String alice = client.register("posting-alice@example.com", "Posting Alice");
    String bob = client.register("posting-bob@example.com", "Posting Bob");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");
    String heldId = client.transferWithKey(alice, bobIban, "12000.00", "f04-held-wire");

    // June 1, five minutes after midnight: an operator approves it. The money
    // moves and the row posts at 00:05 on the FIRST of June.
    CLOCK.set(Instant.parse("2026-06-01T00:05:00Z"));
    mvc.perform(post("/api/v1/admin/transactions/{id}/review", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());

    // May statement: only the deposit posted in May. The wire was requested in
    // May but posted in June, so it must NOT appear - and the balance figures
    // must agree with the rows (opening 0, +20,000 → closing 20,000).
    String mayCsv = csv(alice, aliceId, "2026-05-01", "2026-05-31");
    org.junit.jupiter.api.Assertions.assertTrue(
        mayCsv.contains("20000.0000"), "May statement shows the May deposit");
    org.junit.jupiter.api.Assertions.assertFalse(
        mayCsv.contains("12000.0000"), "May statement must not show a wire that posted in June");

    // June statement: the same wire now appears under the month it moved in.
    String juneCsv = csv(alice, aliceId, "2026-06-01", "2026-06-30");
    org.junit.jupiter.api.Assertions.assertTrue(
        juneCsv.contains("12000.0000"), "June statement shows the June posting");

    // Monthly summary buckets on posting month too: May inflow 20,000 (outflow
    // zero - the wire had not moved yet), June outflow 12,000.
    JsonNode summary = mvc.perform(get("/api/v1/accounts/{id}/summary", aliceId)
            .header("Authorization", "Bearer " + alice)
            .param("months", "3"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().transform(s -> {
          try {
            return json.readTree(s);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    org.junit.jupiter.api.Assertions.assertEquals("20000.0000", summary.get(1).get("inflow").asText());
    org.junit.jupiter.api.Assertions.assertEquals("0", summary.get(1).get("outflow").asText());
    org.junit.jupiter.api.Assertions.assertEquals("0", summary.get(2).get("inflow").asText());
    org.junit.jupiter.api.Assertions.assertEquals("12000.0000", summary.get(2).get("outflow").asText());

    // Request history keeps the request time, and the receipt carries both:
    // created May 31 (submission) but posted June 1 (settlement).
    JsonNode history = mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + alice)
            .param("accountId", aliceId)
            .param("size", "20"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().transform(s -> {
          try {
            return json.readTree(s);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    JsonNode wire = null;
    for (JsonNode row : history.get("items")) {
      if (heldId.equals(row.get("id").asText())) {
        wire = row;
        break;
      }
    }
    org.junit.jupiter.api.Assertions.assertNotNull(wire, "held transfer present in history");
    org.junit.jupiter.api.Assertions.assertTrue(
        wire.get("createdAt").asText().startsWith("2026-05-31"),
        "history createdAt is the request time");
    org.junit.jupiter.api.Assertions.assertTrue(
        wire.get("postedAt").asText().startsWith("2026-06-01"),
        "receipt postedAt is the settlement time");
    org.junit.jupiter.api.Assertions.assertEquals("POSTED", wire.get("status").asText());
  }

  @Test
  void heldApprovalAcrossYearEndPostsIntoTheNewYear() throws Exception {
    // December 31: a review-threshold wire is requested - nothing has moved.
    CLOCK.set(Instant.parse("2026-12-31T22:00:00Z"));
    String alice = client.register("posting-ye@example.com", "Posting Year End");
    String bob = client.register("posting-ye-b@example.com", "Posting Year End B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");
    String heldId = client.transferWithKey(alice, bobIban, "15000.00", "f04-year-end");

    // January 1, a few minutes in: approved. The money moved in the new year.
    CLOCK.set(Instant.parse("2027-01-01T00:05:00Z"));
    mvc.perform(post("/api/v1/admin/transactions/{id}/review", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());

    String decCsv = csv(alice, aliceId, "2026-12-01", "2026-12-31");
    org.junit.jupiter.api.Assertions.assertTrue(decCsv.contains("20000.0000"));
    org.junit.jupiter.api.Assertions.assertFalse(decCsv.contains("15000.0000"),
        "December statement must not show a wire that posted in January");
    String janCsv = csv(alice, aliceId, "2027-01-01", "2027-01-31");
    org.junit.jupiter.api.Assertions.assertTrue(janCsv.contains("15000.0000"),
        "January statement shows the January posting");

    // A second review of the same row cannot re-settle it: it is already
    // POSTED, so the operator endpoint merely acknowledges the (still flagged)
    // row - money does not move a second time.
    mvc.perform(post("/api/v1/admin/transactions/{id}/review", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    JsonNode aliceAcct = mvc.perform(get("/api/v1/accounts")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().transform(s -> {
          try {
            return json.readTree(s);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    org.junit.jupiter.api.Assertions.assertEquals("5000.0000", aliceAcct.get(0).get("balance").asText(),
        "second review must not move money twice");
  }

  @Test
  void declinedTransferNeverPosts() throws Exception {
    String alice = client.register("posting-decline@example.com", "Posting Decline");
    String bob = client.register("posting-decline-b@example.com", "Posting Decline B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");
    String heldId = client.transferWithKey(alice, bobIban, "12000.00", "f04-declined-wire");

    CLOCK.set(Instant.parse("2026-06-01T00:05:00Z"));
    mvc.perform(post("/api/v1/admin/transactions/{id}/decline", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());

    // CANCELLED rows carry no posting time and never enter a statement or a
    // monthly bucket.
    String juneCsv = csv(alice, aliceId, "2026-06-01", "2026-06-30");
    org.junit.jupiter.api.Assertions.assertFalse(
        juneCsv.contains("12000.0000"), "declined wire never posts into any statement");

    JsonNode history = mvc.perform(get("/api/v1/transactions")
            .header("Authorization", "Bearer " + alice)
            .param("accountId", aliceId)
            .param("size", "20"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString().transform(s -> {
          try {
            return json.readTree(s);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    JsonNode wire = null;
    for (JsonNode row : history.get("items")) {
      if (heldId.equals(row.get("id").asText())) {
        wire = row;
        break;
      }
    }
    org.junit.jupiter.api.Assertions.assertNotNull(wire);
    org.junit.jupiter.api.Assertions.assertEquals("CANCELLED", wire.get("status").asText());
    org.junit.jupiter.api.Assertions.assertTrue(wire.get("postedAt").isNull());
  }

  private String csv(String token, String accountId, String from, String to) throws Exception {
    return mvc.perform(get("/api/v1/accounts/{id}/statement.csv", accountId)
            .header("Authorization", "Bearer " + token)
            .param("from", from)
            .param("to", to)
            .accept(MediaType.TEXT_PLAIN))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
  }

  /** A clock a test can wind forward - never frozen across tests (reset in @BeforeEach). */
  private static final class SettableClock extends Clock {
    private Instant instant = Instant.parse("2026-05-31T22:00:00Z");

    void set(Instant value) {
      instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
