package com.bank.platform.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * N03 regression: the admin daily-totals report cuts each row into the UTC
 * day the money POSTED - never the request day - and derives its own \"today\"
 * from the injected business clock, so the report is deterministic at a day
 * boundary. A review-threshold transfer is requested on June 1 at 23:59 and
 * approved on June 2 just after UTC midnight: it must land in the June 2
 * bucket (the money moved there), while the June 1 bucket keeps only the
 * deposit that actually posted on June 1.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReportDailyTotalsDayBoundaryTest {

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
  @Autowired ReportService reports;

  ApiTestClient client;

  @BeforeEach
  void freezeLateOnJuneFirst() {
    // Late evening on June 1: everything posted in the next few seconds is a
    // June 1 row; the report below runs from a clock three days later.
    CLOCK.set(Instant.parse("2026-06-01T23:59:40Z"));
    client = new ApiTestClient(mvc, json);
  }

  @Test
  void dailyTotalsBucketOnPostingDayAcrossUtcMidnight() throws Exception {
    String alice = client.register("n03-totals@example.com", "N03 Totals Alice");
    String bob = client.register("n03-totals-b@example.com", "N03 Totals Bob");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);

    // June 1, 23:59:40 UTC: a 20,000 deposit posts, and a 12,000 transfer is
    // REQUESTED (>= the review threshold, so it is HELD with no posting time).
    client.deposit(alice, aliceId, "20000.00");
    String heldId = client.transferWithKey(alice, bobIban, "12000.00", "n03-boundary-wire");

    // June 2, five seconds after UTC midnight: the operator approves the wire,
    // so the money moved - and the row posted - on June 2. A small deposit
    // also posts on June 2.
    CLOCK.set(Instant.parse("2026-06-02T00:00:05Z"));
    mvc.perform(post("/api/v1/admin/transactions/{id}/review", heldId)
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    client.deposit(alice, aliceId, "5.00");

    // Run the report from a clock on June 3: the 30-day window covers both
    // days. The buckets must follow posting day - June 1 shows only the first
    // deposit, June 2 shows the approved wire plus the second deposit, and
    // June 3 is genuinely empty (no fake zero-day rows, no leakage).
    CLOCK.set(Instant.parse("2026-06-03T12:00:00Z"));
    List<ReportService.DayTotal> totals = reports.dailyTotals(30);

    assertEquals("0", day(totals, "2026-06-01").transferVolume(),
        "the HELD wire was requested on June 1 but must not count there");
    assertEquals(0, day(totals, "2026-06-01").transfers());
    assertEquals(1, day(totals, "2026-06-01").deposits());
    assertEquals("20000.0000", day(totals, "2026-06-01").depositVolume());

    assertEquals(1, day(totals, "2026-06-02").transfers());
    assertEquals("12000.0000", day(totals, "2026-06-02").transferVolume(),
        "the approved wire posts into the day the money moved");
    assertEquals(1, day(totals, "2026-06-02").deposits());
    assertEquals("5.0000", day(totals, "2026-06-02").depositVolume());

    assertEquals(0, day(totals, "2026-06-03").transfers());
    assertEquals(0, day(totals, "2026-06-03").deposits());
    assertEquals("0", day(totals, "2026-06-03").transferVolume());
  }

  private static ReportService.DayTotal day(List<ReportService.DayTotal> totals, String date) {
    return totals.stream()
        .filter(t -> t.date().equals(date))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No daily-totals bucket for " + date));
  }

  /** A clock a test can wind forward - reset in @BeforeEach, never frozen across tests. */
  private static final class SettableClock extends Clock {
    private Instant instant = Instant.parse("2026-06-01T23:59:40Z");

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
