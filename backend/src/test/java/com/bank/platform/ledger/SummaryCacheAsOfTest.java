package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.bank.platform.support.ApiTestClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * the monthly-summary cache key must name the AS-OF MONTH, not just the
 * account and window size. A deposit in January is cached under the January
 * anchor; advancing the business clock to March with NO financial mutation
 * must produce a fresh March window (empty inflow), never the stale January
 * list that a key without the as-of month would keep serving.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SummaryCacheAsOfTest {

  private static final SettableClock CLOCK = new SettableClock(Instant.parse("2026-01-15T12:00:00Z"));

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    java.time.Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired MoneyService money;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void cachedWindowAdvancesAcrossAMonthEndWithoutAnyMutation() throws Exception {
    CLOCK.set(Instant.parse("2026-01-15T12:00:00Z"));
    String alice = client.register("sum-cache@example.com", "Sum Cache");
    String aliceId = client.accountId(alice);
    client.deposit(alice, aliceId, "500.00");

    // One-month window anchored in January: the deposit is inside it.
    TransferDtos.MonthSummary january =
        money.summary("sum-cache@example.com", UUID.fromString(aliceId), 1).get(0);
    assertEquals("2026-01", january.month());
    assertEquals("500.0000", january.inflow());

    // Re-request with the SAME anchor - cache hit, same answer.
    TransferDtos.MonthSummary again =
        money.summary("sum-cache@example.com", UUID.fromString(aliceId), 1).get(0);
    assertEquals("2026-01", again.month());
    assertEquals("500.0000", again.inflow());

    // Advance the clock two months. NO money moved. The window must now be a
    // March window with no inflow - the key includes the as-of month, so the
    // January cache entry cannot be served for March.
    CLOCK.set(Instant.parse("2026-03-15T12:00:00Z"));
    TransferDtos.MonthSummary march =
        money.summary("sum-cache@example.com", UUID.fromString(aliceId), 1).get(0);
    assertEquals("2026-03", march.month(), "the cached window must advance with the clock");
    assertEquals("0", march.inflow(),
        "January's deposit must not leak into the March window");
  }

  static final class SettableClock extends Clock {
    private Instant now;

    SettableClock(Instant now) {
      this.now = now;
    }

    void set(Instant now) {
      this.now = now;
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
      return now;
    }
  }
}
