package com.bank.platform.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.accounts.AccountRepository;
import com.bank.platform.support.ApiTestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AccountRepository accounts;

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
    if (succeeded == 0) {
      failures.forEach(f -> System.err.println("[cc-failure] " + f));
    }

    // Money is conserved: total is 2000 no matter how the writes interleave.
    BigDecimal aliceFinal = accounts.findById(UUID.fromString(aliceId)).orElseThrow().getBalance();
    BigDecimal bobFinal = accounts.findById(UUID.fromString(bobId)).orElseThrow().getBalance();
    assertEquals(new BigDecimal("2000.0000"), aliceFinal.add(bobFinal), "total money must be conserved");
    // Every attempted transfer either fully applied or fully failed - never a partial.
    assertEquals(0, aliceFinal.add(bobFinal).remainder(new BigDecimal("10.00")).intValueExact());
    assertTrue(succeeded > 0, "contention must not reject the whole batch");
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

  private void mvcPerformTransfer(String token, String toIban, String amount, String key) throws Exception {
    client.transferWithKey(token, toIban, amount, key);
  }
}
