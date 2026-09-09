package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * posted operations carry a balanced journal; HELD/CANCELLED intents do
 * not. A deposit credits the customer account and debits the simulator-funding
 * counteraccount; a transfer moves money between the two customer accounts;
 * an approved held transfer journals exactly once (at settlement); a declined
 * one never journals. A duplicate retry can replay the business row but can
 * never create a second journal for the same operation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JournalPostingTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired JournalEntryRepository entries;
  @Autowired JournalLineRepository lines;
  @Autowired JournalService journalService;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void depositAndTransferPostBalancedJournalEntries() throws Exception {
    String alice = client.register("jrn-a@example.com", "Jrn Alice");
    String bob = client.register("jrn-b@example.com", "Jrn Bob");
    UUID aliceId = UUID.fromString(client.accountId(alice));
    UUID bobId = UUID.fromString(client.accountId(bob));
    String bobIban = client.accountIban(bob);

    client.deposit(alice, aliceId.toString(), "500.00");
    client.transfer(alice, bobIban, "120.00");

    // Two postings: the deposit (+500 customer, -500 funding) and the
    // transfer (-120 Alice, +120 Bob).
    assertEquals(2, entries.count(), "one journal entry per posted operation");
    assertEquals(1, entries.countByKind(JournalKind.DEPOSIT));
    assertEquals(4, lines.count(), "two balanced lines per entry");

    BigDecimal aliceLines = sumFor(aliceId);
    BigDecimal bobLines = sumFor(bobId);
    assertEquals(new BigDecimal("380.0000"), balance(aliceId));
    assertEquals(aliceLines, balance(aliceId), "Alice's projection reconciles to her lines");
    assertEquals(bobLines, balance(bobId), "Bob's projection reconciles to his lines");
    assertEquals(new BigDecimal("120.0000"), bobLines);
  }

  @Test
  void heldApprovalJournalsOnceAndDeclineNeverJournals() throws Exception {
    String alice = client.register("jrn-c@example.com", "Jrn C");
    String bob = client.register("jrn-d@example.com", "Jrn D");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");

    String held = client.transferWithKey(alice, bobIban, "12000.00", "jrn-held-1");
    // Holding creates no entry: it is an unreserved intent.
    assertEquals(1, entries.count(), "deposit journaled, HELD intent did not");

    mvc.perform(post("/api/v1/admin/transactions/" + held + "/review")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    assertEquals(2, entries.count(), "approval journals the transfer exactly once");
    assertEquals(1, entries.countByKindAndOperationRef(JournalKind.TRANSFER, held), "one journal per settled operation");
  }

  @Test
  void declinedHeldTransferCreatesNoJournalEntry() throws Exception {
    String alice = client.register("jrn-e@example.com", "Jrn E");
    String bob = client.register("jrn-f@example.com", "Jrn F");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "20000.00");

    String held = client.transferWithKey(alice, bobIban, "11000.00", "jrn-held-2");
    mvc.perform(post("/api/v1/admin/transactions/" + held + "/decline")
            .header("Authorization", "Bearer " + client.adminToken()))
        .andExpect(status().isOk());
    assertEquals(1, entries.count(), "declined intent never settles, so never journals");
    assertEquals(new BigDecimal("20000.0000"), balance(UUID.fromString(aliceId)),
        "no money moved");
  }

  @Test
  void anUnbalancedPostingIsRejectedBeforeAnythingIsWritten() {
    UUID account = UUID.randomUUID();
    assertThrows(TransferValidationException.class, () -> journalService.post(
        JournalKind.DEPOSIT, "fake-1", Instant.now(), "unbalanced",
        JournalService.Posting.account(account, new BigDecimal("10.0000")),
        JournalService.Posting.counter(JournalLine.SIMULATOR_FUNDING, new BigDecimal("9.0000"))));
    assertEquals(0, entries.count(), "nothing was written for a rejected entry");
    assertEquals(0, lines.count());
  }

  @Test
  void duplicateBusinessReplayCannotDuplicateTheJournal() throws Exception {
    String alice = client.register("jrn-g@example.com", "Jrn G");
    String aliceId = client.accountId(alice);
    String key = "jrn-dep-" + UUID.randomUUID();

    org.springframework.http.MediaType json = org.springframework.http.MediaType.APPLICATION_JSON;
    // First funding under the key.
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(json)
            .content("{\"amount\":\"250.00\"}"))
        .andExpect(status().isOk());
    // Identical replay with the same key: the business row replays (no double
    // credit): and the journal must stay at one entry.
    mvc.perform(post("/api/v1/accounts/" + aliceId + "/deposit")
            .header("Authorization", "Bearer " + alice)
            .header("Idempotency-Key", key)
            .contentType(json)
            .content("{\"amount\":\"250.00\"}"))
        .andExpect(status().isOk());
    assertEquals(new BigDecimal("250.0000"), balance(UUID.fromString(aliceId)),
        "replay must not double-credit");
    assertEquals(1, entries.countByKind(JournalKind.DEPOSIT),
        "one deposit row, one journal entry");
  }

  private BigDecimal sumFor(UUID accountId) {
    return lines.findAll().stream()
        .filter(l -> accountId.equals(l.getAccountId()))
        .map(JournalLine::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal balance(UUID accountId) {
    return accounts.findById(accountId).orElseThrow().getBalance();
  }
}
