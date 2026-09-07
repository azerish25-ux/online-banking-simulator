package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.Account;
import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
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
 * a NEW linked operation that moves the money back - the original row is never
 * edited or relabelled. One reversal per original, a mandatory reason and an
 * audited actor, duplicate-reversal protection, and honest refusal when the
 * current account state cannot absorb the reverse movement.
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
    // same posting instant - a reversed transfer is not relabelled as if it
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
