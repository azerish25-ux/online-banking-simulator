package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.audit.AuditLog;
import com.bank.platform.audit.AuditLogRepository;
import com.bank.platform.auth.User;
import com.bank.platform.auth.UserRepository;
import com.bank.platform.notifications.Notification;
import com.bank.platform.notifications.NotificationRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract for the record-and-alert side effects of ledger money events,
 * owned by {@link LedgerEventsService}. The notification copy, the recipient
 * per event, and the audit metadata maps are user-facing product behavior, so
 * they are pinned exactly as emitted - a refactor that moves or rewrites them
 * must update this file deliberately, never silently.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LedgerEventContractTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired UserRepository users;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository audits;

  @Test
  void depositPostsExactNotificationAndAuditMetadata() throws Exception {
    String alice = register("events-a@example.com", "Events Alice");
    String aliceAcc = account(alice);
    String aliceIban = ibanOf(alice);

    deposit(alice, aliceAcc, "100.00");

    Notification n = notification("events-a@example.com", "DEPOSIT_POSTED");
    assertEquals("Deposit received", n.getTitle());
    assertEquals("Deposited $100.00 to " + aliceIban + ".", n.getBody());

    AuditLog log = auditRow("DEPOSIT_POSTED");
    assertEquals("Transaction", log.getEntity());
    assertEquals(Map.of("amount", "100.0000", "to", aliceIban), AuditLog.metadataMap(log.getMetadata()));
  }

  @Test
  void instantTransferNotifiesBothPartiesAndAuditsTheRoute() throws Exception {
    String alice = register("events-b@example.com", "Events B Alice");
    String bob = register("events-c@example.com", "Events B Bob");
    String aliceAcc = account(alice);
    String aliceIban = ibanOf(alice);
    String bobIban = ibanOf(bob);
    deposit(alice, aliceAcc, "200.00");
    String txId = transfer(alice, bobIban, "25.00");

    Notification sent = notification("events-b@example.com", "TRANSFER_SENT");
    assertEquals("Money sent", sent.getTitle());
    assertEquals("Sent $25.00 to " + bobIban + ".", sent.getBody());

    Notification received = notification("events-c@example.com", "TRANSFER_RECEIVED");
    assertEquals("Money received", received.getTitle());
    assertEquals("Received $25.00 from " + aliceIban + ".", received.getBody());

    AuditLog log = auditRow("TRANSFER_POSTED");
    assertEquals(txId, log.getEntityId());
    assertEquals(Map.of("amount", "25.0000", "from", aliceIban, "to", bobIban),
        AuditLog.metadataMap(log.getMetadata()));
  }

  @Test
  void heldTransferTellsOnlyTheSenderNothingMoved() throws Exception {
    String alice = register("events-d@example.com", "Events D Alice");
    String bob = register("events-e@example.com", "Events D Bob");
    String aliceAcc = account(alice);
    String aliceIban = ibanOf(alice);
    String bobIban = ibanOf(bob);
    deposit(alice, aliceAcc, "20000.00");
    String heldId = transfer(alice, bobIban, "12000.00");

    Notification held = notification("events-d@example.com", "TRANSFER_HELD");
    assertEquals("Transfer held for review", held.getTitle());
    assertEquals("Your transfer of $12,000.00 to " + bobIban
        + " is held for operator review; no money has moved yet.", held.getBody());

    // The recipient is not told about an intent - no money has reached them.
    assertNoNotifications("events-e@example.com", "TRANSFER_RECEIVED");

    AuditLog log = auditRow("TRANSFER_HELD");
    assertEquals(heldId, log.getEntityId());
    assertEquals(Map.of("amount", "12000.0000", "from", aliceIban, "to", bobIban),
        AuditLog.metadataMap(log.getMetadata()));
  }

  @Test
  void approvedTransferSettlesAndNotifiesBothParties() throws Exception {
    String alice = register("events-f@example.com", "Events F Alice");
    String bob = register("events-g@example.com", "Events F Bob");
    String aliceAcc = account(alice);
    String aliceIban = ibanOf(alice);
    String bobIban = ibanOf(bob);
    deposit(alice, aliceAcc, "20000.00");
    String heldId = transfer(alice, bobIban, "12000.00");

    decide("events-f@example.com", heldId, "review");

    Notification sent = notification("events-f@example.com", "TRANSFER_SENT");
    assertEquals("Sent $12,000.00 to " + bobIban + ".", sent.getBody());
    Notification received = notification("events-g@example.com", "TRANSFER_RECEIVED");
    assertEquals("Received $12,000.00 from " + aliceIban + ".", received.getBody());

    AuditLog log = auditRow("TRANSFER_APPROVED");
    assertEquals(heldId, log.getEntityId());
    // section 16: the decision reason is part of the audit contract - bounded and
    // plain (the API default when a programmatic caller sends none).
    assertEquals(Map.of("amount", "12000.0000", "from", aliceIban, "to", bobIban,
            "reason", "Operator decision"),
        AuditLog.metadataMap(log.getMetadata()));
  }

  @Test
  void declinedTransferTellsOnlyTheSenderAndAudits() throws Exception {
    String alice = register("events-h@example.com", "Events H Alice");
    String bob = register("events-i@example.com", "Events H Bob");
    String aliceAcc = account(alice);
    String aliceIban = ibanOf(alice);
    String bobIban = ibanOf(bob);
    deposit(alice, aliceAcc, "20000.00");
    String heldId = transfer(alice, bobIban, "12000.00");

    decide("events-h@example.com", heldId, "decline");

    Notification declined = notification("events-h@example.com", "TRANSFER_DECLINED");
    assertEquals("Transfer declined", declined.getTitle());
    assertEquals("Your transfer of $12,000.00 to " + bobIban
        + " was declined by operations; no money moved.", declined.getBody());
    assertNoNotifications("events-i@example.com", "TRANSFER_RECEIVED");

    AuditLog log = auditRow("TRANSFER_DECLINED");
    assertEquals(heldId, log.getEntityId());
    // section 16: the decline reason is recorded on the audit trail.
    assertEquals(Map.of("amount", "12000.0000", "from", aliceIban, "to", bobIban,
            "reason", "Operator decision"),
        AuditLog.metadataMap(log.getMetadata()));
  }

  // ---- helpers -----------------------------------------------------------

  private Notification notification(String email, String type) throws Exception {
    List<Notification> matches = allNotifications(email).stream()
        .filter(n -> n.getType().equals(type))
        .toList();
    assertEquals(1, matches.size(), "expected exactly one " + type + " notification for " + email);
    return matches.get(0);
  }

  private void assertNoNotifications(String email, String type) throws Exception {
    assertTrue(allNotifications(email).stream().noneMatch(n -> n.getType().equals(type)),
        "expected no " + type + " notification for " + email);
  }

  private List<Notification> allNotifications(String email) throws Exception {
    User user = users.findByEmail(email).orElseThrow();
    return notifications.findByUserIdOrderByCreatedAtDesc(user.getId(), PageRequest.of(0, 100))
        .getContent();
  }

  private AuditLog auditRow(String action) {
    Page<AuditLog> page = audits.findByAction(action, PageRequest.of(0, 20));
    assertEquals(1, page.getTotalElements(), "expected exactly one " + action + " audit row");
    return page.getContent().get(0);
  }

  /** The operator decision on a held transfer: "review" approves, "decline" cancels. */
  private void decide(String actorEmail, String txId, String decision) throws Exception {
    mvc.perform(post("/api/v1/admin/transactions/" + txId + "/" + decision)
            .header("Authorization", "Bearer " + login("admin-test@bank.local", "admin-test-123")))
        .andExpect(status().isOk());
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

  private String login(String email, String password) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"email":"%s","password":"%s"}""".formatted(email, password)))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("accessToken").asText();
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

  private String transfer(String token, String toIban, String amount) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/transfers")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", "tx-" + System.nanoTime())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"toIban":"%s","amount":"%s"}""".formatted(toIban, amount)))
        .andExpect(status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), JsonNode.class)
        .get("id").asText();
  }

  private String account(String token) throws Exception {
    return accounts(token).get(0).get("id").asText();
  }

  private String ibanOf(String token) throws Exception {
    return accounts(token).get(0).get("iban").asText();
  }

  private JsonNode accounts(String token) throws Exception {
    MvcResult result = mvc.perform(get("/api/v1/accounts").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
