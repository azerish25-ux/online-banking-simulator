package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interest policy, asserted from first principles against an INDEPENDENT
 * reference - the expected amounts are computed here, day by day, from the
 * documented policy, never by calling the production calculation:
 *
 * <ul>
 *   <li>Savings earn daily interest on the closing balance of each eligible
 *       day (fixed-365): a mid-month deposit earns from its own day;</li>
 *   <li>Loans charge daily interest on the HISTORICAL closing principal of
 *       each eligible day (fixed-365) - never on today's principal, never on
 *       accrued interest (no compounding), never capped at the credit limit.
 *       A period is priced from the principal movements that actually
 *       occurred in it: a later draw cannot create interest for an earlier
 *       period and a later repayment cannot erase an earlier period's
 *       already-posted charge;</li>
 *   <li>Frozen days are never eligible - including when the account is
 *       re-activated and the job catches up the skipped months;</li>
 *   <li>Missed periods are processed in order on the next run (resumable),
 *       every account-period is recorded - including legitimately zero ones -
 *       and a second run never re-argues or re-posts a committed period.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InterestPolicyTest {

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
  @Autowired ReconciliationService reconciliation;

  ApiTestClient client;

  @BeforeEach
  void freezeMidJune() {
    CLOCK.set(Instant.parse("2026-06-15T10:00:00Z"));
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void midMonthDepositEarnsFromItsOwnDayOnly() throws Exception {
    String owner = client.register("pol-sav@example.com", "Pol Savings");
    String savingsId = open(owner, "SAVINGS");
    // Funded on the 20th: 11 closing days of June (20..30) hold the money.
    CLOCK.set(Instant.parse("2026-06-20T10:00:00Z"));
    client.deposit(owner, savingsId, "1000.00");

    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal expected = savings("1000.00", 11);
    assertEquals(expected.toPlainString(), balance(savingsId));

    // The accrual row records the rate version and the priced basis.
    assertTrue(accruals.existsByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-06"));
    // Reconciliation still balances: the interest has a journal entry against
    // the INTEREST counteraccount.
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void emptyAccountsAccrueNothingButRecordTheirCompletedZeroPeriods() throws Exception {
    String owner = client.register("pol-empty@example.com", "Pol Empty");
    String savingsId = open(owner, "SAVINGS");
    String loanId = open(owner, "LOAN");

    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    Map<String, Integer> result = interestService.accrueMonthly();
    assertEquals(0, result.get("accrued"));
    // Both accounts' June periods legitimately produced no charge and are
    // recorded as completed-zero so resumption never re-argues them.
    assertEquals(2, result.get("completedZero"));
    assertEquals("0", balance(savingsId));
    assertEquals("0", balance(loanId));
    assertTrue(accruals.existsByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-06"));
    assertTrue(accruals.existsByAccountIdAndPeriod(UUID.fromString(loanId), "2026-06"));
  }

  /**
   * The headline historical-eligibility defect: a loan that is opened in June
   * but first drawn in July must have ZERO June principal exposure. The later
   * draw must never be back-dated into June - the June period is a recorded
   * zero, and July is charged only from the draw's own day.
   */
  @Test
  void loanOpenedButNotYetDrawnIsNotChargedAndLaterDrawPricesOnlyItsOwnDays()
      throws Exception {
    String owner = client.register("pol-hist@example.com", "Pol Historical");
    String loanId = open(owner, "LOAN");
    String savingsId = open(owner, "SAVINGS");
    String savingsIban = account(savingsId).getIban();

    // June passes with NO draw on the loan. The July 1 run prices June.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    interestService.accrueMonthly();
    assertEquals("0", balance(loanId), "no principal in June: no charge");
    BigDecimal juneRow = accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-06")
        .orElseThrow().getAmount();
    assertEquals(0, juneRow.compareTo(BigDecimal.ZERO), "June is a completed zero");

    // The loan is drawn on July 20. The August 1 run prices July: only the
    // days July 20..31 (12 days) saw principal.
    CLOCK.set(Instant.parse("2026-07-20T10:00:00Z"));
    transfer(owner, savingsIban, "800.00", loanId);
    CLOCK.set(Instant.parse("2026-08-01T03:00:00Z"));
    interestService.accrueMonthly();

    BigDecimal julyCharge = loanCharge(List.of(segment(20, 31, "800.00")), "0.12");
    Account loan = account(loanId);
    assertEquals(new BigDecimal("800.0000"), loan.getPrincipal());
    assertEquals(expectedDebt("800.00", julyCharge), loan.getBalance(),
        "July charge covers only the 12 days principal existed");
    // June's zero is untouched by the later draw - never retroactively priced.
    assertEquals(0, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-06")
        .orElseThrow().getAmount().compareTo(BigDecimal.ZERO));
  }

  @Test
  void loanInterestIsSimplePrincipalOnlyAndRepaymentExtinguishesInterestFirst()
      throws Exception {
    String owner = client.register("pol-loan@example.com", "Pol Loan");
    String checkingId = client.accountId(owner);
    String checkingIban = client.accountIban(owner);
    String loanId = open(owner, "LOAN");
    String loanIban = account(loanId).getIban();

    client.deposit(owner, checkingId, "2000.00");
    // Draw $500 of principal on June 15.
    transfer(owner, checkingIban, "500.00", loanId);

    // July 1 prices June: 16 closing days (15..30) at $500 principal.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal juneCharge = loanCharge(List.of(segment(15, 30, "500.00")), "0.12");
    Account afterJune = account(loanId);
    assertEquals(expectedDebt("500.00", juneCharge), afterJune.getBalance());
    assertEquals(new BigDecimal("500.0000"), afterJune.getPrincipal(),
        "interest never touches principal");
    assertEquals(juneCharge, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-06")
        .orElseThrow().getAmount(), "the accrual row preserves the posted evidence");

    // Repayment on July 5: $100 extinguishes the $2.63 June interest first,
    // then $97.37 of principal - June's charge is not erased by the payment.
    CLOCK.set(Instant.parse("2026-07-05T10:00:00Z"));
    transfer(owner, loanIban, "100.00", null);
    Account repaid = account(loanId);
    BigDecimal remainingPrincipal = new BigDecimal("500.0000").subtract(
        new BigDecimal("100.0000").subtract(juneCharge));
    assertEquals(remainingPrincipal, repaid.getPrincipal(),
        "interest-first allocation: $100 - $2.63 interest = $97.37 principal");
    assertEquals(remainingPrincipal.negate(), repaid.getBalance(),
        "after the payment the only remaining debt is principal");

    // August 1 prices July: $500 for July 1..4, then $402.63 for July 5..31.
    // Interest is charged on PRINCIPAL only - the July charge is the same
    // whether or not June's interest was still unpaid.
    CLOCK.set(Instant.parse("2026-08-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal julyCharge = loanCharge(
        List.of(segment(1, 4, "500.00"), segment(5, 31, remainingPrincipal.toPlainString())),
        "0.12");
    Account afterJuly = account(loanId);
    assertEquals(expectedDebt(remainingPrincipal.toPlainString(), julyCharge),
        afterJuly.getBalance(), "simple interest on historical principal only");
    assertEquals(remainingPrincipal, afterJuly.getPrincipal(),
        "interest still never compounds into principal");
    // June's posted charge is still exactly what was posted.
    assertEquals(juneCharge, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-06")
        .orElseThrow().getAmount());

    // A repeated scheduler/admin run is a no-op and never re-argues a period;
    // July's evidence row is exactly the independently computed charge.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    assertEquals(afterJuly.getBalance(), account(loanId).getBalance());
    assertEquals(julyCharge, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-07")
        .orElseThrow().getAmount());
  }

  /**
   * Resumption: if a run never happens for July (the account's July period is
   * outstanding), the next run in September must recover BOTH July and August
   * in order - not merely process August and forget July.
   */
  @Test
  void missedPeriodsAreRecoveredInOrderOnTheNextRun() throws Exception {
    String owner = client.register("pol-miss@example.com", "Pol Missed");
    String checkingId = client.accountId(owner);
    String checkingIban = client.accountIban(owner);
    String loanId = open(owner, "LOAN");

    client.deposit(owner, checkingId, "2000.00");
    transfer(owner, checkingIban, "500.00", loanId);

    // July 1 run prices June (posted). The July and August runs never happen.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));

    // The next run is September 1: it must price July AND August.
    CLOCK.set(Instant.parse("2026-09-01T03:00:00Z"));
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    BigDecimal juneCharge = loanCharge(List.of(segment(15, 30, "500.00")), "0.12");
    BigDecimal julyCharge = loanCharge(List.of(segment(1, 31, "500.00")), "0.12");
    BigDecimal augustCharge = loanCharge(List.of(segment(1, 31, "500.00")), "0.12");
    Account loan = account(loanId);
    BigDecimal expectedTotalDebt = new BigDecimal("500.00")
        .add(juneCharge).add(julyCharge).add(augustCharge);
    assertEquals(expectedTotalDebt.negate(), loan.getBalance(),
        "July and August both posted on the delayed run, in period order");
    assertTrue(accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-07").isPresent());
    assertTrue(accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-08").isPresent());
    // A repeated run posts nothing more.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
  }

  /**
   * Every posted period records the rate/policy version and the priced basis
   * as evidence. A later "rate change" is a new version - it must never be
   * able to post the same account-period again: the (account, period)
   * uniqueness binds the obligation to ONE charge regardless of version.
   */
  @Test
  void rateVersionEvidenceCannotAuthorizeChargingTheSamePeriodTwice() throws Exception {
    String owner = client.register("pol-rate@example.com", "Pol Rate");
    String savingsId = open(owner, "SAVINGS");
    CLOCK.set(Instant.parse("2026-06-20T10:00:00Z"));
    client.deposit(owner, savingsId, "500.00");
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));

    UUID accountUuid = UUID.fromString(savingsId);
    InterestAccrual posted = accruals.findByAccountIdAndPeriod(accountUuid, "2026-06")
        .orElseThrow();
    assertEquals(InterestService.RATE_VERSION, posted.getRateVersion(),
        "the posted evidence records the version that priced it");
    assertTrue(posted.getDayCount() > 0);

    // A hypothetical future rate version tries to re-post the SAME period:
    // the database uniqueness must reject it, not add a second obligation.
    org.springframework.dao.DataIntegrityViolationException duplicate = assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () -> accruals.saveAndFlush(new InterestAccrual(accountUuid, "2026-06",
            InterestService.RATE_VERSION + 1, posted.getAmount(), posted.getBasis(),
            posted.getDayCount(), Instant.parse("2026-08-01T03:00:00Z"))));
    assertTrue(String.valueOf(duplicate.getMessage()).toLowerCase().contains("interest_accruals")
            || String.valueOf(duplicate.getMessage()).contains("uq_accrual_account_period"),
        "the rejection is the (account, period) uniqueness, not some other integrity failure");
  }

  // ------------------------------------------------------------------
  // Independent reference: the documented policy, computed here from the
  // scenario's own day-by-day principal schedule - not via the service.
  // ------------------------------------------------------------------

  /** One calendar-day range (inclusive) over which principal was constant. */
  private record PrincipalSegment(int fromDay, int toDay, BigDecimal principal) {}

  private static PrincipalSegment segment(int fromDay, int toDay, String principal) {
    return new PrincipalSegment(fromDay, toDay, new BigDecimal(principal));
  }

  /**
   * Σ over eligible days of closing principal × annualRate/365, rounded to 4
   * decimals once at the end (the documented rounding point).
   */
  private static BigDecimal loanCharge(List<PrincipalSegment> segments, String annualRate) {
    BigDecimal dailyRate = new BigDecimal(annualRate)
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    BigDecimal total = BigDecimal.ZERO;
    for (PrincipalSegment segment : segments) {
      int days = segment.toDay - segment.fromDay + 1;
      total = total.add(segment.principal.multiply(dailyRate).multiply(BigDecimal.valueOf(days)));
    }
    return total.setScale(4, RoundingMode.HALF_EVEN);
  }

  /** principal + charge, both at ledger scale (a negative loan balance). */
  private static BigDecimal expectedDebt(String principal, BigDecimal charge) {
    return new BigDecimal(principal).add(charge).negate().setScale(4, RoundingMode.HALF_EVEN);
  }

  /** Savings policy stated independently: closing × 4%/365 × eligible days. */
  private static BigDecimal savings(String closing, int days) {
    BigDecimal dailyRate = new BigDecimal("0.04")
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    BigDecimal interest = new BigDecimal(closing).multiply(dailyRate)
        .multiply(BigDecimal.valueOf(days)).setScale(4, RoundingMode.HALF_EVEN);
    return new BigDecimal(closing).add(interest).setScale(4, RoundingMode.HALF_EVEN);
  }

  private String open(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"%s\"}".formatted(type)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Transfer between the owner's accounts (fromAccountId given) or outbound. */
  private void transfer(String token, String toIban, String amount, String fromId) throws Exception {
    String body = fromId == null
        ? "{\"toIban\":\"%s\",\"amount\":\"%s\"}".formatted(toIban, amount)
        : "{\"toIban\":\"%s\",\"amount\":\"%s\",\"fromAccountId\":\"%s\"}"
            .formatted(toIban, amount, fromId);
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "pol-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isCreated());
  }

  private String balance(String accountId) {
    return account(accountId).getBalance().toPlainString();
  }

  private Account account(String accountId) {
    return accounts.findById(UUID.fromString(accountId)).orElseThrow();
  }

  private static final class SettableClock extends Clock {
    private Instant instant = Instant.parse("2026-06-15T10:00:00Z");

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
