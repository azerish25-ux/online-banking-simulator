package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The concurrency proof. Fires many transfers at the same two accounts from
 * real threads at the same time and asserts the ledger never lies:
 *
 *  - No lost updates: final balance equals arithmetic, not whichever write
 *    happened to land last.
 *  - No deadlocks: opposite-direction transfers resolve (ID-ordered locks).
 *  - Idempotency: N replays of the same key produce exactly one money event.
 *
 * Runs on the H2 test datasource by default (row locks apply there too) and
 * is re-run against real PostgreSQL in CI via the -it profile.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TransferConcurrencyIT {

  /** Shared mutable business clock - the accrual test advances it to July 1st. */
  private static final SettableClock CLOCK = new SettableClock();

  @TestConfiguration
  static class FixedClockConfig {
    @Bean
    @Primary
    java.time.Clock testClock() {
      return CLOCK;
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;
  @Autowired com.bank.platform.ledger.InterestService interestService;
  @Autowired com.bank.platform.ledger.TransactionRepository transactions;

  ApiTestClient client;

  @BeforeEach
  void wire() {
    client = new ApiTestClient(mvc, objectMapper);
  }

  @Test
  void parallelOppositeTransfersConserveMoney() throws Exception {
    String alice = client.register("cc-alice@example.com", "CC Alice");
    String bob = client.register("cc-bob@example.com", "CC Bob");
    String aliceId = client.accountId(alice);
    String bobId = client.accountId(bob);
    String aliceIban = client.accountIban(alice);
    String bobIban = client.accountIban(bob);

    client.deposit(alice, aliceId, "1000.00");
    client.deposit(bob, bobId, "1000.00");

    int n = 24;
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Future<Boolean>> jobs = new ArrayList<>();
    List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    // Half the threads push A→B, half push B→A, simultaneously: the classic
    // deadlock shape, plus contention on both rows.
    for (int i = 0; i < n; i++) {
      boolean aliceSends = i % 2 == 0;
      String token = aliceSends ? alice : bob;
      String toIban = aliceSends ? bobIban : aliceIban;
      jobs.add(pool.submit((Callable<Boolean>) () -> {
        try {
          client.transfer(token, toIban, "10.00");
          return true;
        } catch (Exception e) {
          failures.add(e.getMessage());
          return false;
        }
      }));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "transfers must resolve, not deadlock");
    int succeeded = 0;
    for (Future<Boolean> job : jobs) {
      if (job.get()) succeeded++;
    }
    if (!failures.isEmpty()) {
      failures.forEach(f -> System.err.println("[cc-failure] " + f));
    }

    // F12: no "at least one success" oracle. Every one of the 24 transfers is
    // valid and fully funded ($10 each, balances $1,000, under the review
    // threshold), so ALL must settle - the ID-ordered locking must serialize
    // opposite directions without deadlock, starvation, or spurious rejection.
    assertEquals(24, succeeded, "all 24 funded $10 transfers settle under contention");
    assertTrue(failures.isEmpty(), () -> "expected zero rejections: " + String.join(" | ", failures));

    // Alice sends 12×$10 to Bob and receives 12×$10 back: both net to $1,000.
    BigDecimal aliceFinal = accounts.findById(UUID.fromString(aliceId)).orElseThrow().getBalance();
    BigDecimal bobFinal = accounts.findById(UUID.fromString(bobId)).orElseThrow().getBalance();
    assertEquals(new BigDecimal("1000.0000"), aliceFinal,
        "Alice nets zero across the 24 transfers (12 out, 12 in)");
    assertEquals(new BigDecimal("1000.0000"), bobFinal,
        "Bob nets zero across the 24 transfers (12 in, 12 out)");
    // Money is conserved as well as exact: total is 2000.
    assertEquals(new BigDecimal("2000.0000"), aliceFinal.add(bobFinal), "total money must be conserved");

    // Exact operation count: 24 TRANSFER postings involving Alice, one per
    // settled call - no dupes, no partials (Alice's own deposit row excluded
    // by kind).
    long transferRows = transactions
        .findByAccountSince(UUID.fromString(aliceId), java.time.Instant.EPOCH)
        .stream()
        .filter(tx -> tx.getKind() == com.bank.platform.ledger.TxKind.TRANSFER)
        .count();
    assertEquals(24L, transferRows, "exactly one ledger row per settled transfer");
  }

  @Test
  void idempotentReplaysUnderConcurrencyPostExactlyOnce() throws Exception {
    String alice = client.register("cc-idem@example.com", "CC Idem");
    String bob = client.register("cc-idem-b@example.com", "CC Idem B");
    String aliceId = client.accountId(alice);
    String bobIban = client.accountIban(bob);
    client.deposit(alice, aliceId, "500.00");

    String key = "cc-" + UUID.randomUUID();
    int n = 10;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    List<Future<Boolean>> jobs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      jobs.add(pool.submit((Callable<Boolean>) () -> {
        try {
          mvcPerformTransfer(alice, bobIban, "40.00", key);
          return true;
        } catch (Exception e) {
          return false;
        }
      }));
    }
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

    // Exactly one $40 debit: the winner's row, not ten of them.
    BigDecimal aliceFinal = accounts.findById(UUID.fromString(aliceId)).orElseThrow().getBalance();
    assertEquals(new BigDecimal("460.0000"), aliceFinal,
        "N replays of one key must move money exactly once");
  }

  /**
   * Two overlapping accrual runs (the scheduler at 03:00 colliding with an
   * admin trigger, or two instances) must post interest exactly once per
   * account-month - never twice. The candidate rows are locked FOR UPDATE and
   * re-checked, so the loser of the race skips what the winner already accrued.
   * Against real PostgreSQL this is the authoritative proof; on H2 the timing
   * is best-effort, which is why CI runs this file on the Postgres service.
   */
  @Test
  void parallelAccrualPostsInterestExactlyOnce() throws Exception {
    // Money moves mid-June; both accrual runs price the completed June month
    // (F16) so the period they contend over is deterministic.
    CLOCK.set(java.time.Instant.parse("2026-06-15T10:00:00Z"));
    String alice = client.register("cc-int@example.com", "CC Interest");
    String savingsId = openAccount(alice, "SAVINGS");
    client.deposit(alice, savingsId, "1200.00");
    CLOCK.set(java.time.Instant.parse("2026-07-01T03:00:00Z"));

    int n = 2;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    List<Future<Integer>> jobs = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      jobs.add(pool.submit((Callable<Integer>) () -> {
        start.await(10, TimeUnit.SECONDS);
        return interestService.accrueMonthly().get("accrued");
      }));
    }
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "accrual runs must resolve, not deadlock");

    int totalAccrued = 0;
    for (Future<Integer> job : jobs) {
      totalAccrued += job.get();
    }
    assertEquals(1, totalAccrued, "two overlapping runs must accrue the account once, not twice");
    long interestRows = transactions.findByAccountSince(UUID.fromString(savingsId), java.time.Instant.EPOCH)
        .stream()
        .filter(tx -> tx.getKind() == com.bank.platform.ledger.TxKind.INTEREST)
        .count();
    assertEquals(1L, interestRows, "exactly one INTEREST transaction may exist for the account");
  }

  /** A clock a test can wind forward; defaults to mid-June. */
  private static final class SettableClock extends java.time.Clock {
    private java.time.Instant instant = java.time.Instant.parse("2026-06-15T10:00:00Z");

    void set(java.time.Instant value) {
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
    public java.time.Instant instant() {
      return instant;
    }
  }

  private String openAccount(String token, String type) throws Exception {
    var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .post("/api/v1/accounts")
            .header("Authorization", "Bearer " + token)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"type\":\"%s\"}".formatted(type)))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
        .andReturn();
    return objectMapper.readValue(result.getResponse().getContentAsString(), tools.jackson.databind.JsonNode.class)
        .get("id").asText();
  }

  private void mvcPerformTransfer(String token, String toIban, String amount, String key) throws Exception {
    client.transferWithKey(token, toIban, amount, key);
  }
}
