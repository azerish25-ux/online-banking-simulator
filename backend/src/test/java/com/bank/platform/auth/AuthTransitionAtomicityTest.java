package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * The authoritative transaction boundary for authentication (Issue 3).
 *
 * <p>Every credential pair must be minted UNDER the per-user lock, in the
 * same transaction that validated the proof, bound to the security epoch it
 * was authenticated under, and refused when that epoch is no longer current.
 * Otherwise the interleaving the audit demonstrated stays open: a login that
 * verified its proof before a factor change resumes afterwards and inserts a
 * refresh credential AFTER the revocation commit, so rotation can mint
 * current-version access tokens from a proof the factor has already
 * invalidated.
 */
@SpringBootTest
@ActiveProfiles("test")
class AuthTransitionAtomicityTest {

  @Autowired RefreshService refreshService;
  @Autowired AuthService authService;
  @Autowired UserRepository users;
  @Autowired RefreshTokenRepository refreshTokens;
  @Autowired TotpEnrollmentRepository enrollments;
  @Autowired PasswordEncoder passwords;
  @Autowired TotpService totp;

  private String password = "secret123";

  private User user(String email) {
    return users.save(new User(email, passwords.encode(password), "Atomic Auth"));
  }

  private String codeOf(String secret) {
    return totp.currentCode(secret);
  }

  /**
   * The compressed interleaving: the paused login's issuance runs AFTER the
   * factor change committed. A credential issued from the stale snapshot
   * (the pre-change security version) must be refused outright, and no live
   * refresh row may exist in the old epoch.
   */
  @Test
  void issuanceFromAStaleSecurityEpochIsRefused() {
    User user = user("epoch-stale@example.com");
    int staleVersion = user.getSecurityVersion();

    // The concurrent factor change commits: the version moves on.
    User current = users.findById(user.getId()).orElseThrow();
    current.setSecurityVersion(staleVersion + 1);
    users.save(current);

    // The paused login still holds the stale snapshot (the user row as read
    // before the change). Issuing from it must not mint anything.
    User stale = users.findById(user.getId()).orElseThrow();
    stale.setSecurityVersion(staleVersion);
    assertThrows(BadCredentialsException.class, () -> refreshService.issue(stale),
        "issuing credentials on a stale security epoch must be refused");

    // An explicit stale epoch is refused the same way (the boundary form).
    assertThrows(BadCredentialsException.class,
        () -> refreshService.issue(users.findById(user.getId()).orElseThrow(), staleVersion),
        "an explicit stale epoch must never mint credentials");

    // Nothing survived: no live refresh row in the old epoch.
    List<RefreshToken> live = refreshTokens.findAll().stream()
        .filter(t -> t.getUserId().equals(user.getId()) && !t.isRevoked())
        .toList();
    assertEquals(0, live.size(), "no credential may survive a stale-epoch issuance");
  }

  /**
   * Rotation inherits the epoch check: a stored-but-unrevoked token minted
   * under an older security version must not rotate into a current session.
   */
  @Test
  void rotationRefusesACredentialMintedUnderAnOlderEpoch() {
    User user = user("epoch-rotate@example.com");
    RefreshService.TokenPair pair = refreshService.issue(user);

    // The epoch moves (a factor change elsewhere bumped the version).
    User current = users.findById(user.getId()).orElseThrow();
    current.setSecurityVersion(current.getSecurityVersion() + 1);
    users.save(current);

    assertThrows(BadCredentialsException.class, () -> refreshService.rotate(pair.refreshToken()),
        "a token bound to an older epoch must not rotate into a live session");
  }

  /**
   * The paused-login race with real threads and a barrier: the enable and the
   * paused login's issuance serialize on the per-user lock, so the inserted
   * refresh credential can never land after the revocation commit, and the
   * security version moves exactly once.
   */
  @Test
  void enableAndPausedLoginSerializeIntoOneConsistentOutcome() throws Exception {
    User user = user("enable-race@example.com");
    int versionBefore = user.getSecurityVersion();

    // Password-stage login verified (the user row as the login saw it).
    User snapshot = users.findById(user.getId()).orElseThrow();

    // A pending enrollment for the enable path.
    authService.startTotpSetup(user.getEmail());
    TotpEnrollment pending = enrollments.findUsable(user.getId(), java.time.Instant.now())
        .stream().findFirst().orElseThrow();
    String pendingSecret = pending.getPendingSecret();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<RefreshService.TokenPair> pausedPair = new AtomicReference<>();
    AtomicBoolean pausedRejected = new AtomicBoolean();
    AtomicBoolean enableOk = new AtomicBoolean();

    pool.submit(() -> {
      ready.countDown();
      try {
        start.await(10, TimeUnit.SECONDS);
        pausedPair.set(refreshService.issue(snapshot));
      } catch (BadCredentialsException expected) {
        pausedRejected.set(true);
      } catch (Exception e) {
        pausedRejected.set(true);
      }
    });
    pool.submit(() -> {
      ready.countDown();
      try {
        start.await(10, TimeUnit.SECONDS);
        authService.enableTotp(user.getEmail(), codeOf(pendingSecret), null, null);
        enableOk.set(true);
      } catch (Exception e) {
        enableOk.set(false);
      }
    });

    assertTrue(ready.await(10, TimeUnit.SECONDS), "both workers must start");
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the race must resolve");

    // The factor transition happened exactly once and the version moved once.
    User after = users.findById(user.getId()).orElseThrow();
    assertTrue(after.isTotpEnabled(), "the enable must have won");
    assertEquals(versionBefore + 1, after.getSecurityVersion(),
        "the security version moves exactly once");

    // Whatever the interleaving, NO live credential may exist in the old
    // epoch: either the paused login was refused, or its token was minted
    // before the enable and revoked by it. After the storm, zero unrevoked
    // rows bound to the pre-change version remain.
    List<RefreshToken> staleLive = refreshTokens.findAll().stream()
        .filter(t -> t.getUserId().equals(user.getId()) && !t.isRevoked())
        .toList();
    if (pausedPair.get() != null) {
      // The paused login won the lock BEFORE the enable: its token was
      // minted under the old epoch and must have been revoked by the
      // enable's revocation. Rotating it must fail.
      assertThrows(BadCredentialsException.class,
          () -> refreshService.rotate(pausedPair.get().refreshToken()),
          "a credential minted before the factor change must be dead after it");
    } else {
      assertTrue(pausedRejected.get(), "the paused login must be refused after the change");
      assertEquals(0, staleLive.size(),
          "a refused issuance must leave no live credential behind");
    }
    // And any live rows must carry the NEW epoch.
    for (RefreshToken token : staleLive) {
      assertEquals(after.getSecurityVersion(), token.getSecurityVersion(),
          "every surviving credential must be bound to the current epoch");
    }
  }

  /**
   * Enable against enable: two parallel promotions of two pending
   * enrollments. Only one may win; the loser must be refused (its target
   * state moved under it), the version must move exactly once, and exactly
   * one enrollment may be consumed.
   */
  @Test
  void parallelEnablesMoveTheVersionExactlyOnce() throws Exception {
    User user = user("double-enable@example.com");
    int versionBefore = user.getSecurityVersion();

    // Two independent pending enrollments.
    authService.startTotpSetup(user.getEmail());
    String secretA = enrollments.findUsable(user.getId(), java.time.Instant.now())
        .stream().findFirst().orElseThrow().getPendingSecret();
    authService.startTotpSetup(user.getEmail());
    String secretB = enrollments.findUsable(user.getId(), java.time.Instant.now())
        .stream()
        .filter(e -> !e.getPendingSecret().equals(secretA))
        .findFirst().orElseThrow().getPendingSecret();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicBoolean firstOk = new AtomicBoolean();
    AtomicBoolean secondOk = new AtomicBoolean();

    pool.submit(() -> {
      ready.countDown();
      try {
        start.await(10, TimeUnit.SECONDS);
        authService.enableTotp(user.getEmail(), codeOf(secretA), null, null);
        firstOk.set(true);
      } catch (Exception e) {
        firstOk.set(false);
      }
    });
    pool.submit(() -> {
      ready.countDown();
      try {
        start.await(10, TimeUnit.SECONDS);
        authService.enableTotp(user.getEmail(), codeOf(secretB), null, null);
        secondOk.set(true);
      } catch (Exception e) {
        secondOk.set(false);
      }
    });

    assertTrue(ready.await(10, TimeUnit.SECONDS), "both workers must start");
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "the race must resolve");

    // Exactly one promotion may succeed (the second is a replacement that
    // requires the old factor's proof, which neither racing call supplies).
    assertEquals(1, (firstOk.get() ? 1 : 0) + (secondOk.get() ? 1 : 0),
        "exactly one enable may win the race");

    User after = users.findById(user.getId()).orElseThrow();
    assertTrue(after.isTotpEnabled());
    assertEquals(versionBefore + 1, after.getSecurityVersion(),
        "the version must move exactly once, never N+1 twice");

    // Both pending enrollments are consumed: the promoted one, and the other
    // superseded by the superseding policy (nothing promotable may survive).
    long consumed = enrollments.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
        .filter(TotpEnrollment::isConsumed)
        .count();
    assertEquals(2, consumed, "both enrollments are consumed: one promoted, one superseded");
  }
}
