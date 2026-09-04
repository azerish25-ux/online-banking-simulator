package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;

/**
 * The reuse-detection proof under concurrency. Two threads present the SAME
 * refresh token at the same time (two tabs, or a thief racing a legitimate
 * refresh). Rotation must consume the token atomically: exactly one rotation
 * wins, and the loser burns the whole family - so the winning token is revoked
 * too and the account must re-authenticate. Without the atomic conditional
 * consume, both rotations used to pass the in-Java revoked check and mint two
 * parallel valid sessions, defeating family-burn theft detection.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshRotationConcurrencyTest {

  @Autowired RefreshService refreshService;
  @Autowired UserRepository users;
  @Autowired RefreshTokenRepository refreshTokens;

  @Test
  void concurrentPresentationOfOneTokenConsumesItOnceAndBurnsTheFamily() throws Exception {
    User user = users.save(new User("rot-race@example.com", "not-a-real-hash", "Rotation Racer"));
    RefreshService.TokenPair first = refreshService.issue(user);
    String presented = first.refreshToken();

    int n = 2;
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch ready = new CountDownLatch(n);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger failures = new AtomicInteger();
    AtomicInteger unexpected = new AtomicInteger();
    AtomicReference<String> winnerToken = new AtomicReference<>();

    for (int i = 0; i < n; i++) {
      pool.submit(() -> {
        ready.countDown();
        try {
          if (!start.await(10, TimeUnit.SECONDS)) {
            return;
          }
          RefreshService.TokenPair pair = refreshService.rotate(presented);
          winnerToken.set(pair.refreshToken());
          successes.incrementAndGet();
        } catch (BadCredentialsException expected) {
          // The loser: the token was already consumed - the family must burn.
          failures.incrementAndGet();
        } catch (Exception e) {
          unexpected.incrementAndGet();
        }
      });
    }
    assertTrue(ready.await(10, TimeUnit.SECONDS), "both threads must start");
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "rotations must resolve");

    assertEquals(0, unexpected.get(), "no unexpected failures");
    assertEquals(1, successes.get(), "exactly one rotation may win the token");
    assertEquals(1, failures.get(), "the loser must be treated as a replay");

    // The family is burned: every stored token for this user is revoked,
    // including the token the winner just minted.
    refreshTokens.findAll().stream()
        .filter(t -> t.getUserId().equals(user.getId()))
        .forEach(t -> assertTrue(t.isRevoked(), "family burn must revoke every token"));

    String winner = winnerToken.get();
    org.junit.jupiter.api.Assertions.assertNotNull(winner);
    assertThrows(BadCredentialsException.class, () -> refreshService.rotate(winner),
        "the burned family must not produce further sessions");
  }
}
