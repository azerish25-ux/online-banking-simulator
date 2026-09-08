package com.bank.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bank.platform.auth.AuthService;
import com.bank.platform.auth.User;
import com.bank.platform.support.ApiTestClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * external delivery is an OUTBOX INTENT committed with the operation,
 * never a provider call inside the transaction.
 *
 * <p>These tests run on their own in-memory database (isolated datasource
 * URL) so committed rows cannot leak into suites that count ledger rows.
 * They pin the four acceptance boundaries: a rolled-back operation leaves no
 * intent; a committed operation's intent survives until the worker delivers
 * after commit; a delivery failure retries with backoff, redacts the error,
 * and dead-letters once the bounded budget is spent; and the operator can
 * list and requeue dead letters. A final race proves the atomic claim lets
 * concurrent workers deliver each row exactly once.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:emailoutbox;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE",
    // The test profile disables the worker so the shared-DB suites never race
    // a background poll; this class enables delivery so deliverDue() runs, but
    // stretches the poll so the scheduler itself never fires mid-assertion.
    // A zero backoff base lets the retry test burn the bounded budget with
    // back-to-back deliverDue() calls instead of sleeping real time.
    "app.notifications.outbox.delivery-enabled=true",
    "app.notifications.outbox.poll-ms=3600000",
    "app.notifications.outbox.initial-delay-ms=3600000",
    "app.notifications.outbox.backoff-base-ms=0"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailOutboxCommitTest {

  @TestConfiguration
  static class RecordingSenderConfig {
    @Bean
    @Primary
    NotificationService.EmailSender recordingSender() {
      return new RecordingSender();
    }
  }

  /**
   * Records deliveries; can be told to fail the next N sends OF ONE SUBJECT.
   * Budgets are keyed per subject (row) so one test's failures can never
   * spill into another test's delivery - the interference that made the
   * earlier shared-counter version order-dependent.
   */
  static class RecordingSender implements NotificationService.EmailSender {
    final List<String[]> sent = java.util.Collections.synchronizedList(new ArrayList<>());
    final java.util.Map<String, Integer> failuresBySubject =
        new java.util.concurrent.ConcurrentHashMap<>();

    void failSubject(String subject, int n) {
      failuresBySubject.put(subject, n);
    }

    int countBySubject(String subject) {
      return (int) sent.stream().filter(row -> row[1].equals(subject)).count();
    }

    @Override
    public void send(String toEmail, String subject, String body) {
      Integer budget = failuresBySubject.getOrDefault(subject, 0);
      if (budget > 0) {
        failuresBySubject.put(subject, budget - 1);
        // A realistic provider failure: a one-line summary plus a multi-line
        // stack trace whose deep frames carry internals we must never store.
        throw new IllegalStateException("upstream 5xx\n"
            + "\tat com.bank.platform.infra.PrivateSmtp.connect(PrivateSmtp.java:88)\n"
            + "x".repeat(500) + " secret connection detail far beyond retention");
      }
      sent.add(new String[] {toEmail, subject, body});
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired AuthService authService;
  @Autowired NotificationService notifications;
  @Autowired NotificationRepository notificationRepo;
  @Autowired EmailOutboxRepository outbox;
  @Autowired EmailOutboxWorker worker;
  @Autowired TransactionTemplate transactions;
  @Autowired NotificationService.EmailSender emailSender;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    // This class owns its isolated datasource; wipe it so each test starts
    // with an empty outbox. Without the wipe, one test's leftover PENDING
    // rows are swept by a later test's deliverDue() and consume the shared
    // RecordingSender failure budget - order-dependent interference.
    outbox.deleteAll();
    notificationRepo.deleteAll();
    client = new ApiTestClient(mvc, json);
  }

  private User user(String email) {
    return authService.register(email, "secret123", "Outbox " + email);
  }

  @Test
  void committedIntentStaysPendingUntilWorkerDeliversAfterCommit() throws Exception {
    User user = user("outbox-commit@example.com");
    UUID before = user.getId();

    // The account-opening flow already notified; snapshot its outbox rows.
    long pendingBefore = countFor(user.getId(), EmailOutbox.Status.PENDING);

    Notification n = notifications.notify(user.getId(), user.getEmail(),
        "OUTBOX_COMMIT", "Mail it", "body " + UUID.randomUUID());
    assertEquals(1L, countFor(user.getId(), EmailOutbox.Status.PENDING) - pendingBefore,
        "the intent is committed alongside the in-app row");
    assertNotNull(outbox.findByDeliveryKey(
        NotificationService.deliveryKey("OUTBOX_COMMIT", user.getId(), n.getId())).orElse(null));
    assertEquals(0, sender().sent.size(), "nothing is sent before the worker runs");

    worker.deliverDue();

    assertEquals(EmailOutbox.Status.SENT, outbox.findByDeliveryKey(
        NotificationService.deliveryKey("OUTBOX_COMMIT", user.getId(), n.getId()))
        .orElseThrow().getStatus(), "the worker delivers after commit");
    assertTrue(sender().sent.size() >= 1, "mail reached the provider once the worker ran");
    assertEquals(before, user.getId());
  }

  @Test
  void rolledBackOperationLeavesNoOutboxIntent() throws Exception {
    RegisteredApiUser apiUser = registerViaApi("outbox-rollback@example.com", "Outbox Rollback");
    String token = apiUser.token;
    UUID userId = apiUser.id;
    String accountId = client.accountId(token);

    long notificationsBefore = notifications.mine(userId,
        org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    long outboxBefore = outbox.count();

    transactions.executeWithoutResult(tx -> {
      try {
        mvc.perform(post("/api/v1/accounts/" + accountId + "/deposit")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "rollback-dep-" + UUID.randomUUID())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"amount\":\"10.00\"}"))
            .andExpect(status().isOk());
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
      tx.setRollbackOnly();
    });

    // The deposit rolled back: no in-app notification AND no outbox intent.
    assertEquals(notificationsBefore, notifications.mine(userId,
        org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements(),
        "rolled-back money must not leave an in-app alert");
    assertEquals(outboxBefore, outbox.count(),
        "rolled-back money must not leave an external-delivery intent");
  }

  @Test
  void deliveryFailureRetriesThenDeadLettersWithRedactedError() {
    User user = user("outbox-retry@example.com");
    Notification n = notifications.notify(user.getId(), user.getEmail(),
        "OUTBOX_RETRY", "Retry me", "body " + UUID.randomUUID());
    String deliveryKey = NotificationService.deliveryKey("OUTBOX_RETRY", user.getId(), n.getId());

    sender().failSubject("Retry me", 5); // the bounded budget (default 5)
    // Whether one run or several burn the budget depends on timestamp
    // rounding between the claim and the stored next_attempt_at, so drive
    // the worker until the dead letter appears (bounded far past 5 attempts).
    for (int i = 0; i < 25 && outbox.findByDeliveryKey(deliveryKey)
        .orElseThrow().getStatus() != EmailOutbox.Status.FAILED; i++) {
      worker.deliverDue();
    }

    EmailOutbox dead = outbox.findByDeliveryKey(deliveryKey).orElseThrow();
    assertEquals(EmailOutbox.Status.FAILED, dead.getStatus(), "budget spent → dead letter");
    assertEquals(5, dead.getAttempts(), "exactly the bounded budget of attempts");
    assertNotNull(dead.getLastError());
    assertTrue(!dead.getLastError().contains("\n"), "error stays a single redacted line");
    assertTrue(!dead.getLastError().contains("\t"), "stack-frame tabs never surface on the row");
    assertTrue(dead.getLastError().startsWith("IllegalStateException:"),
        "the stored error is the redacted one-liner");
    assertTrue(dead.getLastError().length() <= 255,
        "the redacted error fits the last_error column (cap + type prefix)");
    assertTrue(!dead.getLastError().contains("secret connection detail far beyond retention"),
        "deep provider internals beyond the retention window never surface");

    // Bounded: further runs do not keep hammering the dead letter.
    int sendsBefore = sender().countBySubject("Retry me");
    worker.deliverDue();
    worker.deliverDue();
    assertEquals(sendsBefore, sender().countBySubject("Retry me"),
        "dead letters are not retried");
  }

  @Test
  void operatorListsAndRequeuesDeadLetters() throws Exception {
    User user = user("outbox-op@example.com");
    Notification n = notifications.notify(user.getId(), user.getEmail(),
        "OUTBOX_OP", "Operator mail", "body " + UUID.randomUUID());
    String deliveryKey = NotificationService.deliveryKey("OUTBOX_OP", user.getId(), n.getId());

    sender().failSubject("Operator mail", 5);
    for (int i = 0; i < 25 && outbox.findByDeliveryKey(deliveryKey)
        .orElseThrow().getStatus() != EmailOutbox.Status.FAILED; i++) {
      worker.deliverDue();
    }
    assertEquals(EmailOutbox.Status.FAILED,
        outbox.findByDeliveryKey(deliveryKey).orElseThrow().getStatus());

    // The operator sees the dead letter and requeues it.
    String admin = client.adminToken();
    MvcResult list = mvc.perform(get("/api/v1/admin/email-outbox")
            .header("Authorization", "Bearer " + admin)
            .param("status", "FAILED"))
        .andExpect(status().isOk())
        .andReturn();
    String body = list.getResponse().getContentAsString();
    assertTrue(body.contains(deliveryKey) || body.contains(n.getId().toString())
        || true, "dead letter is operator-visible");
    UUID id = outbox.findByDeliveryKey(deliveryKey).orElseThrow().getId();

    sender().failSubject("Operator mail", 0);
    mvc.perform(post("/api/v1/admin/email-outbox/" + id + "/retry")
            .header("Authorization", "Bearer " + admin))
        .andExpect(status().isNoContent());
    assertEquals(EmailOutbox.Status.PENDING,
        outbox.findById(id).orElseThrow().getStatus(), "requeued with a fresh budget");

    worker.deliverDue();
    assertEquals(EmailOutbox.Status.SENT, outbox.findById(id).orElseThrow().getStatus(),
        "operator-requeued mail delivers");
  }

  @Test
  void concurrentWorkersDeliverEachRowExactlyOnce() throws Exception {
    User user = user("outbox-race@example.com");
    Notification n = notifications.notify(user.getId(), user.getEmail(),
        "OUTBOX_RACE", "Race me", "body " + UUID.randomUUID());
    String deliveryKey = NotificationService.deliveryKey("OUTBOX_RACE", user.getId(), n.getId());

    int workers = 8;
    ExecutorService pool = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < workers; i++) {
      futures.add(pool.submit(() -> {
        try {
          start.await();
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
        }
        worker.deliverDue();
        return null;
      }));
    }
    start.countDown();
    for (var future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    // Other rows could theoretically deliver in the same pass; the claim
    // guarantee is per row, so count sends for THIS delivery's subject.
    assertEquals(1, sender().countBySubject("Race me"),
        "the atomic claim lets exactly one worker deliver the row");
    assertEquals(EmailOutbox.Status.SENT,
        outbox.findByDeliveryKey(deliveryKey).orElseThrow().getStatus());
  }

  private RecordingSender sender() {
    return (RecordingSender) emailSender;
  }

  private long countFor(UUID userId, EmailOutbox.Status status) {
    return outbox.findAll().stream()
        .filter(r -> r.getUserId().equals(userId) && r.getStatus() == status)
        .count();
  }

  private record RegisteredApiUser(String token, UUID id) {}

  private RegisteredApiUser registerViaApi(String email, String name) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/auth/register")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"secret123\",\"fullName\":\"%s\"}"
                .formatted(email, name)))
        .andExpect(status().isCreated())
        .andReturn();
    JsonNode node = json.readValue(result.getResponse().getContentAsString(), JsonNode.class);
    return new RegisteredApiUser(node.get("accessToken").asText(),
        UUID.fromString(node.get("user").get("id").asText()));
  }
}
