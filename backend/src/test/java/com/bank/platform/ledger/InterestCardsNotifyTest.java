package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InterestCardsNotifyTest {

  /** Shared mutable business clock: money moves mid-June, interest prices June. */
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
  @Autowired InterestService interestService;
  @Autowired InterestAccrualRepository accruals;

  ApiTestClient client;

  @BeforeEach
  void freezeMidJune() {
    CLOCK.set(Instant.parse("2026-06-15T10:00:00Z"));
    client = new ApiTestClient(mvc, objectMapper);
  }

  /** One calendar-day range (inclusive) over which principal was constant. */
  private record PrincipalSegment(int fromDay, int toDay, BigDecimal principal) {}

  private static PrincipalSegment seg(int fromDay, int toDay, String principal) {
    return new PrincipalSegment(fromDay, toDay, new BigDecimal(principal));
  }

  /** Independent daily-principal loan charge: Σ closing × 12%/365, one round at the end. */
  private static BigDecimal loanCharge(List<PrincipalSegment> segments) {
    BigDecimal dailyRate = new BigDecimal("0.12")
        .divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    BigDecimal total = BigDecimal.ZERO;
    for (PrincipalSegment segment : segments) {
      int days = segment.toDay - segment.fromDay + 1;
      total = total.add(segment.principal.multiply(dailyRate).multiply(BigDecimal.valueOf(days)));
    }
    return total.setScale(4, RoundingMode.HALF_EVEN);
  }

  /** A loan's expected balance: -(principal + independently computed charge). */
  private static String expectedLoanBalance(String principal, BigDecimal charge) {
    return new BigDecimal(principal).add(charge).negate().setScale(4, RoundingMode.HALF_EVEN)
        .toPlainString();
  }

  @Test
  void savingsEarnLoanChargesAndSecondRunIsNoOp() throws Exception {
    String token = register("p8a@example.com", "P Eight");
    String savingsId = openAccount(token, "SAVINGS");
    String loanId = openAccount(token, "LOAN");

    deposit(token, savingsId, "1200.00");
    // Spend the loan into overdraft: -200 (principal 200).
    String savingsIban = accountIban(token, savingsId);
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"200.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isCreated());

    // Beyond the 1000 principal limit is rejected.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"900.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isUnprocessableEntity());

    // July 1st, 03:00: the accrual run prices June.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    // Savings: 1400 held from June 15-30 (16 closing days) at 4% actual/365.
    // Loan: 200 drawn June 15 → 16 closing days at 200 × 12% actual/365.
    expectBalance(token, savingsId, dailyInterest("1400.00", 16));
    expectBalance(token, loanId, expectedLoanBalance("200.00",
        loanCharge(List.of(seg(15, 30, "200.00")))));
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, savingsId, dailyInterest("1400.00", 16));
    expectBalance(token, loanId, expectedLoanBalance("200.00",
        loanCharge(List.of(seg(15, 30, "200.00")))));
  }

  @Test
  void loanAtFullLimitIsChargedNotForgivenAndNeverBreaksTheRun() throws Exception {
    String token = register("p8d@example.com", "P Eight D");
    String savingsId = openAccount(token, "SAVINGS");
    String loanId = openAccount(token, "LOAN");

    deposit(token, savingsId, "1200.00");
    String savingsIban = accountIban(token, savingsId);
    // Draw the loan right up to its $1000 limit: $999 principal.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"999.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isCreated());

    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    // The daily-principal charge on the $999 principal (16 closing days) is NOT
    // forgiven at the full limit: the debt deepens past -credit_limit .
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, loanId, expectedLoanBalance("999.00",
        loanCharge(List.of(seg(15, 30, "999.00")))));
    expectBalance(token, savingsId, dailyInterest("2199.00", 16));

    // A second run is a no-op (nothing accrued twice), including the maxed loan.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, loanId, expectedLoanBalance("999.00",
        loanCharge(List.of(seg(15, 30, "999.00")))));
  }

  /**
   * Frozen accounts do not accrue and are not retroactively charged after
   * re-activation. A loan drawn in June and frozen on July 10 is charged for
   * June and for July 1..9 only: never July 10..31. When it is unfrozen in
   * August, the next run prices August 5..31 and skips the frozen days, using
   * the recorded status history rather than today's principal or status.
   */
  @Test
  void frozenLoanIsChargedOnlyForItsActiveDays() throws Exception {
    String token = register("p8f@example.com", "P Eight F");
    String admin = client.adminToken();
    String savingsId = openAccount(token, "SAVINGS");
    String loanId = openAccount(token, "LOAN");

    deposit(token, savingsId, "1200.00");
    String savingsIban = accountIban(token, savingsId);
    // Draw $500 of principal on June 15 into savings.
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"500.00","fromAccountId":"%s"}"""
                .formatted(savingsIban, loanId)))
        .andExpect(status().isCreated());

    // July 1 run prices June for both accounts.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    BigDecimal juneCharge = loanCharge(List.of(seg(15, 30, "500.00")));
    expectBalance(token, loanId, expectedLoanBalance("500.00", juneCharge));

    // Freeze the loan on July 10. The August 1 run (which prices July) sees a
    // frozen account and skips it entirely: nothing accrues, nothing zeroes.
    CLOCK.set(Instant.parse("2026-07-10T10:00:00Z"));
    mvc.perform(post("/api/v1/admin/accounts/" + loanId + "/freeze")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    CLOCK.set(Instant.parse("2026-08-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    assertTrue(accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-07").isEmpty(),
        "no July accrual while the loan was frozen at run time");

    // Unfreeze on August 5. The September 1 run must recover July and August
    // from the status history: July 1..9 (9 days) and August 5..31 (27 days).
    CLOCK.set(Instant.parse("2026-08-05T09:00:00Z"));
    mvc.perform(post("/api/v1/admin/accounts/" + loanId + "/unfreeze")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isOk());
    CLOCK.set(Instant.parse("2026-09-01T03:00:00Z"));
    interestService.accrueMonthly();

    BigDecimal julyCharge = loanCharge(List.of(seg(1, 9, "500.00")));
    BigDecimal augustCharge = loanCharge(List.of(seg(5, 31, "500.00")));
    expectBalance(token, loanId, expectedLoanBalance("500.00",
        juneCharge.add(julyCharge).add(augustCharge)));
    assertEquals(9, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-07")
        .orElseThrow().getDayCount(), "July priced its 9 active days only");
    assertEquals(27, accruals.findByAccountIdAndPeriod(UUID.fromString(loanId), "2026-08")
        .orElseThrow().getDayCount(), "August priced its 27 active days only");
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
  }

  /** The policy formula stated independently: closing × 4%/365 per eligible day. */
  private static String dailyInterest(String closing, int days) {
    BigDecimal daily = new BigDecimal("0.04").divide(BigDecimal.valueOf(365), 12, RoundingMode.HALF_EVEN);
    BigDecimal interest = new BigDecimal(closing).multiply(daily).multiply(BigDecimal.valueOf(days))
        .setScale(4, RoundingMode.HALF_EVEN);
    return new BigDecimal(closing).add(interest).toPlainString();
  }

  @Test
  void cardsIssueOnceListMaskedAndFreeze() throws Exception {
    String token = register("p8b@example.com", "P Eight B");
    String checkingId = accountId(token);

    MvcResult issued = mvc.perform(post("/api/v1/accounts/" + checkingId + "/cards")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pan").isNotEmpty())
        .andExpect(jsonPath("$.cvv").isNotEmpty())
        .andReturn();
    JsonNode node = objectMapper.readValue(issued.getResponse().getContentAsString(), JsonNode.class);
    assertTrue(luhnValid(node.get("pan").asText()), "issued PAN must be Luhn-valid");
    String cardId = node.get("id").asText();

    // Listing never leaks the PAN.
    mvc.perform(get("/api/v1/accounts/" + checkingId + "/cards")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].last4").value(node.get("pan").asText().substring(12)))
        .andExpect(jsonPath("$[0].pan").doesNotExist());

    mvc.perform(post("/api/v1/cards/" + cardId + "/freeze")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FROZEN"));
  }

  @Test
  void transferNotifiesRecipientAndReadClears() throws Exception {
    String alice = register("p8c-a@example.com", "P Eight CA");
    String bob = register("p8c-b@example.com", "P Eight CB");
    String aliceId = accountId(alice);
    String bobIban = accountIban(bob);

    deposit(alice, aliceId, "50.00");
    mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"5.00"}""".formatted(bobIban)))
        .andExpect(status().isCreated());

    MvcResult unread = mvc.perform(get("/api/v1/notifications/unread-count")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andReturn();
    int before = objectMapper.readValue(unread.getResponse().getContentAsString(), JsonNode.class)
        .get("unread").asInt();
    assertTrue(before >= 1, "recipient should have notifications");

    MvcResult list = mvc.perform(get("/api/v1/notifications?size=5")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andReturn();
    String notifId = objectMapper.readValue(list.getResponse().getContentAsString(), JsonNode.class)
        .get("content").get(0).get("id").asText();
    mvc.perform(post("/api/v1/notifications/" + notifId + "/read")
            .header("Authorization", "Bearer " + bob))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.read").value(true));
  }

  private static boolean luhnValid(String pan) {
    int sum = 0;
    boolean doubleIt = false;
    for (int i = pan.length() - 1; i >= 0; i--) {
      int digit = pan.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) digit -= 9;
      }
      sum += digit;
      doubleIt = !doubleIt;
    }
    return pan.length() == 16 && sum % 10 == 0;
  }

  private String register(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"secret123","fullName":"%s"}""".formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
  }

  private String openAccount(String token, String type) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"type":"%s"}""".formatted(type)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value(type))
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private void deposit(String token, String accountId, String amount) throws Exception {
    mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "dep-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"amount":"%s"}""".formatted(amount)))
        .andExpect(status().isOk());
  }

  private void expectBalance(String token, String accountId, String expected) throws Exception {
    mvc.perform(get("/api/v1/accounts/" + accountId).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.balance").value(expected));
  }

  private String accountIban(String token) throws Exception {
    return accountField(token, null, "iban");
  }

  private String accountIban(String token, String accountId) throws Exception {
    return accountField(token, accountId, "iban");
  }

  private String accountId(String token) throws Exception {
    return accountField(token, null, "id");
  }

  private String accountField(String token, String accountId, String field) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode arr = objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    for (JsonNode node : arr) {
      if (accountId == null || node.get("id").asText().equals(accountId)) {
        return node.get(field).asText();
      }
    }
    throw new IllegalStateException("account not found");
  }

  /** A clock a test can wind forward: reset in @BeforeEach. */
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
