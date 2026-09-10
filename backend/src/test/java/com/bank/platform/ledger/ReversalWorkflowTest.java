package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * The authorized reversal workflow (V29): a posted instruction is reversed by
 * a NEW linked operation that moves the money back: the original row is never
 * edited or relabelled. One reversal per original, a mandatory reason and an
 * audited actor, duplicate-reversal protection, and honest refusal when the
 * current account state cannot absorb the reverse movement. A transfer that
 * touches a LOAN leg is refused outright: a loan repayment extinguishes
 * interest before principal, and the plain reverse movement would book that
 * interest back as fresh principal.
 */
@SpringBootTest
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReversalWorkflowTest {

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
  @Autowired TransactionRepository transactions;
  @Autowired JournalEntryRepository journalEntries;
  @Autowired ReconciliationService reconciliation;
  @Autowired InterestService interestService;
  @Autowired PrincipalMovementRepository principalMovements;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    CLOCK.set(Instant.parse("2026-06-15T10:00:00Z"));
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void postedTransferIsReversedWithALinkedJournalEntryAndOriginalUntouched()
      throws Exception {
    String alice = client.register("rev-a@example.com", "Rev Alice");
    String bob = client.register("rev-b@example.com", "Rev Bob");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String bobIban = client.accountIban(bob);
    UUID bobId = UUID.fromString(accountByIban(bobIban).getId().toString());
    client.deposit(alice, aliceId.toString(), "1000.00");

    String transferId = client.transfer(alice, bobIban, "100.00");
    UUID transferUuid = UUID.fromString(transferId);
    Transaction original = transactions.findById(transferUuid).orElseThrow();
    JournalEntry originalEntry = journalEntries.findByKindAndOperationRef(
        JournalKind.TRANSFER, transferId);
    assertNotNull(originalEntry, "the posted transfer has its journal entry");

    String reversalId = reverse(client.adminToken(), transferUuid, "Customer confirmed double charge");

    // Money moved back: Bob paid Alice $100.
    assertEquals(new BigDecimal("1000.0000"), account(aliceId).getBalance());
    assertEquals(new BigDecimal("0.0000"), account(bobId).getBalance());

    Transaction reversal = transactions.findById(UUID.fromString(reversalId)).orElseThrow();
    assertEquals(TxKind.REVERSAL, reversal.getKind());
    assertEquals(TxStatus.POSTED, reversal.getStatus(), "the reversal itself is a settled posting");
    assertEquals(transferUuid, reversal.getReversesTransactionId());
    assertEquals("Customer confirmed double charge", reversal.getReversalReason());
    assertEquals(bobId, reversal.getFromAccountId(), "the payee pays the money back");
    assertEquals(aliceId, reversal.getToAccountId());

    // The ORIGINAL row keeps its history: still POSTED, still $100, still the
    // same posting instant: a reversed transfer is not relabelled as if it
    // had never settled.
    Transaction after = transactions.findById(transferUuid).orElseThrow();
    assertEquals(TxStatus.POSTED, after.getStatus());
    assertEquals(original.getPostedAt(), after.getPostedAt());
    assertEquals(original.getAmount(), after.getAmount());

    // The reversal journals as a linked entry pointing at the original entry.
    JournalEntry reversalEntry = journalEntries.findByKindAndOperationRef(
        JournalKind.REVERSAL, reversalId);
    assertNotNull(reversalEntry, "the reversal has its own journal entry");
    assertEquals(originalEntry.getId(), reversalEntry.getReversesEntryId(),
        "the reversal entry links back to the original posting");
    // Nothing balances differently: 1000 + 0 and the journal agrees.
    assertTrue(reconciliation.reconcile().balanced());
    assertTrue(reconciliation.reconcile().operationConsistent());
    assertTrue(reconciliation.reconcile().reversalConsistent());
  }

  @Test
  void depositIsReversedBackToTheFundingRail() throws Exception {
    String alice = client.register("rev-c@example.com", "Rev C");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    client.deposit(alice, aliceId.toString(), "500.00");

    Transaction deposit = transactions.findByAccountSince(aliceId, Instant.EPOCH).stream()
        .filter(tx -> tx.getKind() == TxKind.DEPOSIT)
        .findFirst().orElseThrow();
    JournalEntry depositEntry = journalEntries.findByKindAndOperationRef(
        JournalKind.DEPOSIT, deposit.getId().toString());
    assertNotNull(depositEntry);

    String reversalId = reverse(client.adminToken(), deposit.getId(), "Deposit keyed twice");

    Transaction reversal = transactions.findById(UUID.fromString(reversalId)).orElseThrow();
    assertEquals(deposit.getId(), reversal.getReversesTransactionId());
    assertNull(reversal.getToAccountId(), "a deposit reversal has no payee");
    assertEquals(aliceId, reversal.getFromAccountId());
    assertEquals("0.0000", account(aliceId).getBalance().toPlainString(),
        "the deposit money returned to the rail");
    JournalEntry reversalEntry = journalEntries.findByKindAndOperationRef(
        JournalKind.REVERSAL, reversalId);
    assertEquals(depositEntry.getId(), reversalEntry.getReversesEntryId());
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void duplicateReversalIsRejectedExactlyOnce() throws Exception {
    String alice = client.register("rev-d@example.com", "Rev D");
    String bob = client.register("rev-e@example.com", "Rev E");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId.toString(), "1000.00");
    String transferId = client.transfer(alice, bobIban, "60.00");

    reverse(client.adminToken(), UUID.fromString(transferId), "First reversal");
    // A second operator (or a retried request) cannot reverse it again.
    mvc.perform(post("/api/v1/admin/transactions/" + transferId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Second attempt\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(1L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null
            && tx.getReversesTransactionId().toString().equals(transferId))
        .count(), "exactly one reversal per original");
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void reversalIsRefusedWhenThePayeeNoLongerHoldsTheFunds() throws Exception {
    String alice = client.register("rev-f@example.com", "Rev F");
    String bob = client.register("rev-g@example.com", "Rev G");
    String carol = client.register("rev-h@example.com", "Rev H");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String bobIban = client.accountIban(bob);
    String carolIban = client.accountIban(carol);
    client.deposit(alice, aliceId.toString(), "1000.00");
    String transferId = client.transfer(alice, bobIban, "200.00");

    // Bob spends the money before ops reverses the original transfer.
    String bobToken = bob;
    client.transfer(bobToken, carolIban, "200.00");

    mvc.perform(post("/api/v1/admin/transactions/" + transferId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Sender error\"}"))
        .andExpect(status().isUnprocessableEntity());
    // Nothing moved: no reversal row, balances unchanged.
    assertEquals(0L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null).count());
    assertEquals(new BigDecimal("800.0000"), account(aliceId).getBalance());
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void reversalRequiresAnAdminActorAndAReson() throws Exception {
    String alice = client.register("rev-i@example.com", "Rev I");
    String bob = client.register("rev-j@example.com", "Rev J");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId.toString(), "300.00");
    String transferId = client.transfer(alice, bobIban, "40.00");

    // A customer cannot reverse.
    mvc.perform(post("/api/v1/admin/transactions/" + transferId + "/reverse")
            .header("Authorization", "Bearer " + alice)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Please undo\"}"))
        .andExpect(status().isForbidden());
    // A reason is mandatory.
    mvc.perform(post("/api/v1/admin/transactions/" + transferId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());
    assertEquals(0L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null).count());
  }

  @Test
  void operatorAndCustomerWireShapesCarryTheRightReversalState() throws Exception {
    String alice = client.register("rev-k@example.com", "Rev K");
    String bob = client.register("rev-l@example.com", "Rev L");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId.toString(), "1000.00");
    String transferId = client.transfer(alice, bobIban, "90.00");

    // The reverse response is the NEW reversal row, reason and linkage on it.
    MvcResult res = mvc.perform(post("/api/v1/admin/transactions/" + transferId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Wrong amount entered\"}"))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode body = objectMapper.readTree(res.getResponse().getContentAsString());
    String reversalId = body.get("id").asText();
    assertEquals("REVERSAL", body.get("kind").asText());
    assertEquals("Wrong amount entered", body.get("reversalReason").asText());
    assertEquals(transferId, body.get("reversesTransactionId").asText());

    // The operator list marks the ORIGINAL as reversed: its row is untouched,
    // so only the reversal index can say so: and shows the reason on the row.
    MvcResult list = mvc.perform(get("/api/v1/admin/transactions?size=50")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode content = objectMapper.readTree(list.getResponse().getContentAsString())
        .get("content");
    assertEquals(reversalId, findById(content, transferId).get("reversalId").asText(),
        "the operator list tells the console the original already has a reversal");
    assertEquals("Wrong amount entered",
        findById(content, reversalId).get("reversalReason").asText());

    // The customer feed keeps the operator's note off the wire entirely.
    MvcResult feed = mvc.perform(get("/api/v1/transactions?accountId=" + aliceId + "&size=50")
            .header("Authorization", "Bearer " + alice))
        .andExpect(status().isOk())
        .andReturn();
    JsonNode items = objectMapper.readTree(feed.getResponse().getContentAsString()).get("items");
    JsonNode feedReversal = findById(items, reversalId);
    assertEquals(transferId, feedReversal.get("reversesTransactionId").asText(),
        "the customer still sees the reversal is of their transfer");
    assertTrue(feedReversal.has("reversalReason")
        && feedReversal.get("reversalReason").isNull(),
        "the operator's reason is never exposed on a customer-facing feed");
  }

  // ------------------------------------------------------------------
  // Loan-leg reversals. The interest-first repayment allocation has no
  // plain inverse: reversing the movement would reclassify extinguished
  // interest as principal, so the workflow refuses before any mutation.
  // ------------------------------------------------------------------

  @Test
  void reversalOfAnInterestOnlyLoanRepaymentIsRefusedBeforeAnyMutation()
      throws Exception {
    String alice = client.register("rev-loan-a@example.com", "Rev Loan A");
    String aliceIban = client.accountIban(alice);
    String loanId = openLoan(alice);
    String loanIban = account(UUID.fromString(loanId)).getIban();

    // Draw 100 of principal on June 15; the July 1 run accrues June interest.
    transferFrom(alice, loanId, aliceIban, "100.00");
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal juneInterest = loanCharge(List.of(segment(15, 30, "100.00")), "0.12");
    assertEquals(new BigDecimal("100.0000").add(juneInterest).negate(),
        account(UUID.fromString(loanId)).getBalance());

    // An interest-only repayment: principal stays 100, only the interest is
    // extinguished.
    CLOCK.set(Instant.parse("2026-07-10T10:00:00Z"));
    String repayTxId = client.transfer(alice, loanIban, juneInterest.toPlainString());
    assertEquals(new BigDecimal("100.0000"), account(UUID.fromString(loanId)).getPrincipal());
    assertEquals(new BigDecimal("100.0000").negate(), account(UUID.fromString(loanId)).getBalance());

    // Reversing the repayment would book the interest back as principal: the
    // debt composition cannot be restored from recorded evidence, so the
    // workflow refuses before any mutation.
    mvc.perform(post("/api/v1/admin/transactions/" + repayTxId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Operator error\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(0L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null).count(),
        "no reversal row may exist for a refused loan-leg reversal");
    assertEquals(new BigDecimal("100.0000"), account(UUID.fromString(loanId)).getPrincipal());
    assertEquals(new BigDecimal("100.0000").negate(), account(UUID.fromString(loanId)).getBalance());
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void reversalOfAMixedLoanRepaymentIsRefusedAndAccrualStaysOnTruePrincipal()
      throws Exception {
    String alice = client.register("rev-loan-d@example.com", "Rev Loan D");
    String aliceIban = client.accountIban(alice);
    String loanId = openLoan(alice);
    String loanIban = account(UUID.fromString(loanId)).getIban();

    transferFrom(alice, loanId, aliceIban, "100.00");
    CLOCK.set(Instant.parse("2026-07-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal juneInterest = loanCharge(List.of(segment(15, 30, "100.00")), "0.12");

    // A mixed repayment: part interest, part principal.
    CLOCK.set(Instant.parse("2026-07-10T10:00:00Z"));
    String repayTxId = client.transfer(alice, loanIban, "10.00");
    BigDecimal principalAfterRepay = new BigDecimal("100.0000")
        .subtract(new BigDecimal("10.0000").subtract(juneInterest));
    assertEquals(principalAfterRepay, account(UUID.fromString(loanId)).getPrincipal());

    mvc.perform(post("/api/v1/admin/transactions/" + repayTxId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Operator error\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(principalAfterRepay, account(UUID.fromString(loanId)).getPrincipal(),
        "the refused reversal must not reclassify the interest component");
    // The principal-movement history agrees with the row: one draw, one
    // repayment component, nothing else.
    assertEquals(principalAfterRepay,
        principalMovements.sumPostedBefore(UUID.fromString(loanId),
            Instant.parse("2027-01-01T00:00:00Z")).orElseThrow());

    // The next accrual prices the TRUE principal, never a misclassified one.
    CLOCK.set(Instant.parse("2026-08-01T03:00:00Z"));
    assertEquals(1, interestService.accrueMonthly().get("accrued"));
    BigDecimal julyCharge = loanCharge(List.of(
        segment(1, 9, "100.0000"),
        segment(10, 31, principalAfterRepay.toPlainString())), "0.12");
    assertEquals(principalAfterRepay.add(julyCharge).negate(),
        account(UUID.fromString(loanId)).getBalance());
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void reversalOfATransferDrawnFromALoanIsRefused() throws Exception {
    String alice = client.register("rev-loan-b@example.com", "Rev Loan B");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    String aliceIban = client.accountIban(alice);
    String loanId = openLoan(alice);

    String drawTxId = transferFrom(alice, loanId, aliceIban, "100.00");

    // The reverse movement would credit the loan as a repayment (or, once
    // interest exists, misallocate it): refusal, not guessed accounting.
    mvc.perform(post("/api/v1/admin/transactions/" + drawTxId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Drawn by mistake\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(0L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null).count());
    assertEquals(new BigDecimal("100.0000"), account(UUID.fromString(loanId)).getPrincipal());
    assertEquals(new BigDecimal("100.0000").negate(), account(UUID.fromString(loanId)).getBalance());
    assertEquals(new BigDecimal("100.0000"), account(aliceId).getBalance());
    assertTrue(reconciliation.reconcile().balanced());
  }

  @Test
  void reversalOfAFullyDrawnLoanTransferIsRefusedWithoutAnAffordabilityGuess()
      throws Exception {
    String alice = client.register("rev-loan-c@example.com", "Rev Loan C");
    String aliceIban = client.accountIban(alice);
    String loanId = openLoan(alice);

    // The draw consumed the whole credit limit; the reverse movement would
    // push new borrowing onto the loan. The loan-leg rule refuses before any
    // movement instead of letting affordability arithmetic decide.
    String drawTxId = transferFrom(alice, loanId, aliceIban, "1000.00");
    mvc.perform(post("/api/v1/admin/transactions/" + drawTxId + "/reverse")
            .header("Authorization", "Bearer " + client.adminToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Wrong account\"}"))
        .andExpect(status().isBadRequest());
    assertEquals(new BigDecimal("1000.0000"), account(UUID.fromString(loanId)).getPrincipal());
    assertEquals(new BigDecimal("1000.0000").negate(), account(UUID.fromString(loanId)).getBalance());
    assertEquals(0L, transactions.findAll().stream()
        .filter(tx -> tx.getReversesTransactionId() != null).count());
  }

  /** Opens a LOAN account (credit limit 1000) and returns its id. */
  private String openLoan(String token) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"LOAN\"}"))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  /** Transfer FROM a specific owned account (e.g. a loan draw); returns the id. */
  private String transferFrom(String token, String fromId, String toIban, String amount)
      throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "rev-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"toIban\":\"%s\",\"amount\":\"%s\",\"fromAccountId\":\"%s\"}"
                .formatted(toIban, amount, fromId)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private record PrincipalSegment(int fromDay, int toDay, BigDecimal principal) {}

  private static PrincipalSegment segment(int fromDay, int toDay, String principal) {
    return new PrincipalSegment(fromDay, toDay, new BigDecimal(principal));
  }

  /** Fixed-365 charge over constant-principal day ranges, rounded once at 4dp. */
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

  private JsonNode findById(JsonNode array, String id) {
    for (JsonNode row : array) {
      if (row.get("id").asText().equals(id)) {
        return row;
      }
    }
    throw new AssertionError("no row with id " + id + " in " + array);
  }

  private String reverse(String admin, UUID transactionId, String reason) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/admin/transactions/" + transactionId + "/reverse")
            .header("Authorization", "Bearer " + admin)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"%s\"}".formatted(reason)))
        .andReturn();
    if (result.getResponse().getStatus() != 200) {
      throw new AssertionError("reverse failed: " + result.getResponse().getStatus()
          + " " + result.getResponse().getContentAsString());
    }
    return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private Account account(UUID id) {
    return accounts.findById(id).orElseThrow();
  }

  private Account accountByIban(String iban) {
    return accounts.findAll().stream()
        .filter(a -> a.getIban().equals(iban))
        .findFirst().orElseThrow();
  }

  private static final class SettableClock extends java.time.Clock {
    private Instant instant = Instant.parse("2026-06-15T10:00:00Z");

    void set(Instant value) {
      instant = value;
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
