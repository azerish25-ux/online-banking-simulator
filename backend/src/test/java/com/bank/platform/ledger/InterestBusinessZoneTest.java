package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business-zone accrual. The pricing window walks the business calendar of
 * the configured {@code app.interest.business-zone}: one interval per
 * calendar date, each boundary derived in that zone. A zone with daylight
 * saving time must produce exactly as many day intervals as the month has
 * dates (November 2026 in America/Halifax holds 30 dates, however many hours
 * it holds), and a leap February holds 29. Postings at or after the month's
 * final midnight in the business zone belong to the priced month, not the
 * next one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.TestPropertySource(
    properties = "app.interest.business-zone=America/Halifax")
class InterestBusinessZoneTest {

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
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired InterestAccrualRepository accruals;
  @Autowired InterestService interestService;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    CLOCK.set(Instant.parse("2026-12-01T03:00:00Z"));
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void novemberInHalifaxPricesThirtyDatesNotThirtyOneHours() throws Exception {
    // The account exists before the priced month, so its first supported
    // month is not November itself.
    CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
    String owner = client.register("zone-nov@example.com", "Zone November");
    String savingsId = open(owner, "SAVINGS");

    // Funded on November 1 local: 30 closing dates hold the money.
    CLOCK.set(Instant.parse("2026-11-01T04:00:00Z"));
    client.deposit(owner, savingsId, "1000.00");

    // December 1, 03:00 in the business zone prices November and nothing else.
    CLOCK.set(Instant.parse("2026-12-01T07:00:00Z"));
    Map<String, Integer> result = interestService.accrueMonthly();
    assertEquals(1, result.get("accrued"));

    InterestAccrual posted = accruals
        .findByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-11").orElseThrow();
    assertEquals(30, posted.getDayCount(), "November 2026 holds 30 business dates");
    BigDecimal expected = savings("1000.00", 30);
    assertEquals(0, expected.compareTo(posted.getAmount()),
        "the charge prices 30 daily intervals, one per calendar date");
    assertEquals(expected.add(new BigDecimal("1000.0000")),
        account(savingsId).getBalance());

    // The next run re-argues nothing: one completed accrual per account and
    // period, and the balance is exactly what one posting produced.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    assertEquals(expected.add(new BigDecimal("1000.0000")),
        account(savingsId).getBalance());
  }

  @Test
  void novemberInUtcPricesTheSameThirtyDates() throws Exception {
    // The UTC control: the default zone must produce the identical day count
    // and amount for the same month, so the fix changes nothing at UTC.
    CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
    String owner = client.register("zone-utc@example.com", "Zone UTC");
    String savingsId = open(owner, "SAVINGS");

    CLOCK.set(Instant.parse("2026-11-01T12:00:00Z"));
    client.deposit(owner, savingsId, "1000.00");

    CLOCK.set(Instant.parse("2026-12-01T00:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    InterestAccrual posted = accruals
        .findByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-11").orElseThrow();
    assertEquals(30, posted.getDayCount());
    assertEquals(0, savings("1000.00", 30).compareTo(posted.getAmount()));
  }

  @Test
  void leapFebruaryPricesTwentyNineDates() throws Exception {
    CLOCK.set(Instant.parse("2028-01-15T10:00:00Z"));
    String owner = client.register("zone-leap@example.com", "Zone Leap");
    String savingsId = open(owner, "SAVINGS");

    CLOCK.set(Instant.parse("2028-02-01T05:00:00Z"));
    client.deposit(owner, savingsId, "1000.00");

    CLOCK.set(Instant.parse("2028-03-01T07:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    InterestAccrual posted = accruals
        .findByAccountIdAndPeriod(UUID.fromString(savingsId), "2028-02").orElseThrow();
    assertEquals(29, posted.getDayCount(), "February 2028 holds 29 dates");
  }

  @Test
  void postingAtTheFinalMidnightBelongsToThePricedMonth() throws Exception {
    CLOCK.set(Instant.parse("2026-10-15T10:00:00Z"));
    String owner = client.register("zone-edge@example.com", "Zone Edge");
    String savingsId = open(owner, "SAVINGS");

    // 00:00 local on November 30 (03:00/04:00Z, inside the window): the last
    // closing date of the month still sees the money.
    CLOCK.set(Instant.parse("2026-11-30T04:00:00Z"));
    client.deposit(owner, savingsId, "1000.00");

    CLOCK.set(Instant.parse("2026-12-01T07:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    InterestAccrual posted = accruals
        .findByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-11").orElseThrow();
    assertEquals(1, posted.getDayCount(), "exactly one closing date held the money");
    BigDecimal expected = savings("1000.00", 1);
    assertEquals(0, expected.compareTo(posted.getAmount()));
    // A posting AFTER the window end must not be consumed by the priced month.
    assertTrue(accruals.findByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-12")
        .isEmpty(), "December is not priced by the November run");
  }

  private String open(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"type\":\"%s\"}".formatted(type)))
        .andReturn();
    if (result.getResponse().getStatus() != 201) {
      throw new AssertionError("open failed: " + result.getResponse().getStatus());
    }
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Savings policy stated independently: closing x 4%/365 x eligible days. */
  private static BigDecimal savings(String closing, int days) {
    BigDecimal dailyRate = new BigDecimal("0.04")
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    return new BigDecimal(closing).multiply(dailyRate)
        .multiply(BigDecimal.valueOf(days)).setScale(4, RoundingMode.HALF_EVEN);
  }

  private Account account(String accountId) {
    return accounts.findById(UUID.fromString(accountId)).orElseThrow();
  }

  private static final class SettableClock extends Clock {
    private Instant instant = Instant.parse("2026-12-01T03:00:00Z");

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
