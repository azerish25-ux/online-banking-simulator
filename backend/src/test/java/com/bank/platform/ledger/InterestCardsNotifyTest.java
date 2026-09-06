package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

  @BeforeEach
  void freezeMidJune() {
    CLOCK.set(Instant.parse("2026-06-15T10:00:00Z"));
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

    // July 1st, 03:00 - the accrual run prices June.
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    // Savings: 1400 held from June 15-30 (16 closing days) at 4% actual/365.
    // Loan: simple 1% monthly on the $200 principal - $2.00.
    expectBalance(token, savingsId, dailyInterest("1400.00", 16));
    expectBalance(token, loanId, "-202.0000");
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, savingsId, dailyInterest("1400.00", 16));
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
    // The 1% monthly charge ($9.99 on the $999 principal) is NOT forgiven at
    // the full limit: the debt deepens past -credit_limit - the point of F16.
    assertEquals(2, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, loanId, "-1008.9900");
    expectBalance(token, savingsId, dailyInterest("2199.00", 16));

    // A second run is a no-op (nothing accrued twice), including the maxed loan.
    assertEquals(0, interestService.accrueMonthly().get("accrued"));
    expectBalance(token, loanId, "-1008.9900");
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

  /** A clock a test can wind forward - reset in @BeforeEach. */
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
