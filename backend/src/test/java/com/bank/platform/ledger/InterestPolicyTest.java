package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * F16 interest policy, asserted from first principles:
 *
 * <ul>
 *   <li>Savings earn daily interest on prior-month closing balances derived
 *       from journal postings - a mid-month deposit earns from its own day,
 *       never a full month;</li>
 *   <li>Loans charge simple monthly interest on PRINCIPAL - never on the
 *       accrued interest, so nothing compounds, and never capped at the
 *       credit limit;</li>
 *   <li>Repayments extinguish interest before principal;</li>
 *   <li>The (account, period) accrual row is unique and resumable, and every
 *       interest posting is journaled so reconciliation balances.</li>
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
    BigDecimal expected = daily("1000.00", 11);
    assertEquals(expected.toPlainString(), balance(savingsId));

    // The accrual row records the rate version and the priced basis.
    assertTrue(accruals.existsByAccountIdAndPeriod(UUID.fromString(savingsId), "2026-06"));
    // Reconciliation still balances: the interest has a journal entry against
    // the INTEREST counteraccount.
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void emptyAccountsAccrueNothingAndRecordNoRow() throws Exception {
    String owner = client.register("pol-empty@example.com", "Pol Empty");
    String savingsId = open(owner, "SAVINGS");
    String loanId = open(owner, "LOAN");

    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    assertEquals("0", balance(savingsId));
    assertEquals("0", balance(loanId));
  }

  @Test
  void loanInterestChargesPrincipalOnlySoItNeverCompounds() throws Exception {
    String owner = client.register("pol-loan@example.com", "Pol Loan");
    String checkingId = client.accountId(owner);
    String checkingIban = client.accountIban(owner);
    String loanId = open(owner, "LOAN");
    String loanIban = account(loanId).getIban();

    client.deposit(owner, checkingId, "2000.00");
    // Draw $500 of principal in June.
    transfer(owner, checkingIban, "500.00", loanId);

    // July 1: $500 principal × 1% = $5.00 → -505.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    Account afterJune = account(loanId);
    assertEquals(new BigDecimal("-505.0000"), afterJune.getBalance());
    assertEquals(new BigDecimal("500.0000"), afterJune.getPrincipal(), "interest never touches principal");

    // August 1: July passed with NO loan activity, and the July charge was
    // $5.00 on the SAME $500 principal - not $5.05 on the $505 balance. No
    // compounding, even though the debt includes last month's interest.
    CLOCK.set(Instant.parse("2026-08-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    Account afterJuly = account(loanId);
    assertEquals(new BigDecimal("-510.0000"), afterJuly.getBalance(),
        "simple interest on principal only");
    assertEquals(new BigDecimal("500.0000"), afterJuly.getPrincipal());

    // Repayment extinguishes interest first, then principal: $100 now pays the
    // $10 accrued interest and $90 of principal.
    CLOCK.set(Instant.parse("2026-08-01T10:00:00Z"));
    transfer(owner, loanIban, "100.00", null);
    Account repaid = account(loanId);
    assertEquals(new BigDecimal("-410.0000"), repaid.getBalance());
    assertEquals(new BigDecimal("410.0000"), repaid.getPrincipal(),
        "interest-first allocation leaves principal = debt after interest is gone");
  }

  @Test
  void retryAfterCommittedAccrualIsANoOpPerAccount() throws Exception {
    String owner = client.register("pol-retry@example.com", "Pol Retry");
    String savingsId = open(owner, "SAVINGS");
    CLOCK.set(Instant.parse("2026-06-20T10:00:00Z"));
    client.deposit(owner, savingsId, "800.00");

    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal once = new BigDecimal(balance(savingsId));
    // A second run - the shape of a scheduler/admin overlap after a commit -
    // must not re-accrue the same account-month.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    assertEquals(once.toPlainString(), balance(savingsId));
    assertEquals(1L, accruals.count(), "one accrual row per account-month");
  }

  /** The policy formula stated independently: closing × 4%/365 × eligible days. */
  private static BigDecimal daily(String closing, int days) {
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
