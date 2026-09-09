package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;

/**
 * The refresh-token FAMILY lifecycle, beyond the single same-token race
 * ({@link RefreshRotationConcurrencyTest}) and the endpoint-level flow
 * ({@code AuthFlowTest.refreshRotatesAndLogoutRevokes}).
 *
 * <p>Each login starts a family of one; every rotation consumes the presented
 * token and mints its child, so a device chain is gen0 → gen1 → gen2 ... . Reuse
 * detection must treat a replay of ANY family member: an ancestor whose row
 * was consumed by a legitimate rotation, a logged-out device, or a stolen
 * copy: as theft and burn the WHOLE lineage, including tokens that were never
 * replayed. Logging out one device, by contrast, revokes only that device's
 * row and must leave a sibling device fully usable. A burn never bricks the
 * account: a fresh login starts a new, working family.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshFamilyLifecycleTest {

  @Autowired RefreshService refreshService;
  @Autowired UserRepository users;
  @Autowired RefreshTokenRepository refreshTokens;

  @Test
  void replayingAnAncestorBurnsTheWholeDescendantLineage() {
    User user = user("family-burn@example.com");
    // A device chain of three generations, each rotation legitimate.
    RefreshService.TokenPair gen0 = refreshService.issue(user);
    RefreshService.TokenPair gen1 = refreshService.rotate(gen0.refreshToken());
    RefreshService.TokenPair gen2 = refreshService.rotate(gen1.refreshToken());
    assertNotEquals(gen0.refreshToken(), gen1.refreshToken());
    assertNotEquals(gen1.refreshToken(), gen2.refreshToken());
    // The newest generation is live right now.
    RefreshService.TokenPair check = refreshService.rotate(gen2.refreshToken());
    assertNotEquals(gen2.refreshToken(), check.refreshToken());

    // The thief replays a deep ancestor (gen0), which a legitimate rotation
    // consumed long ago. Reuse detection must burn the ENTIRE family: the
    // descendant rows that were never replayed die with it.
    assertThrows(BadCredentialsException.class,
        () -> refreshService.rotate(gen0.refreshToken()),
        "replaying an ancestor is theft and must be refused");

    // A sibling that never left this user's control is dead too, because its
    // family was burned: no silent successor can be minted from it.
    assertThrows(BadCredentialsException.class,
        () -> refreshService.rotate(check.refreshToken()),
        "the burned family must not mint further sessions from any member");
    assertThrows(BadCredentialsException.class,
        () -> refreshService.rotate(gen2.refreshToken()),
        "every lineage member is revoked after the burn");

    List<RefreshToken> family = rowsOf(user.getId());
    assertTrue(family.size() >= 4, "the whole chain must still be stored: " + family.size());
    for (RefreshToken token : family) {
      assertTrue(token.isRevoked(), "family burn must revoke every stored token");
    }
  }

  @Test
  void aFreshLoginStartsANewWorkingFamilyAfterTheOldOneBurned() {
    User user = user("family-renew@example.com");
    RefreshService.TokenPair gen0 = refreshService.issue(user);
    RefreshService.TokenPair gen1 = refreshService.rotate(gen0.refreshToken());

    // Burn it (replay the consumed ancestor).
    assertThrows(BadCredentialsException.class,
        () -> refreshService.rotate(gen0.refreshToken()));

    // The account is NOT bricked: a fresh login starts a new family whose
    // first rotation works, and the old lineage stays dead.
    RefreshService.TokenPair fresh = refreshService.issue(user);
    RefreshService.TokenPair rotated = refreshService.rotate(fresh.refreshToken());
    assertNotEquals(fresh.refreshToken(), rotated.refreshToken());

    // The renewed family is alive; the burned one is not resurrected: the two
    // rows of the burned lineage stay revoked while the new family's rows are
    // the only usable ones.
    List<RefreshToken> stored = rowsOf(user.getId());
    assertEquals(2, stored.stream()
            .filter(t -> t.getTokenHash().equals(RefreshService.sha256(gen0.refreshToken()))
                || t.getTokenHash().equals(RefreshService.sha256(gen1.refreshToken())))
            .filter(RefreshToken::isRevoked)
            .count(),
        "the burned ancestor chain must stay revoked after the renewal");
    RefreshToken newest = stored.stream()
        .filter(t -> !t.isRevoked())
        .findFirst().orElseThrow(() -> new AssertionError("the renewed family must be live"));
    assertEquals(RefreshService.sha256(rotated.refreshToken()), newest.getTokenHash(),
        "the only usable row is the renewed family's newest generation");
  }

  @Test
  void loggingOutOneDeviceLeavesASiblingDeviceFullyUsable() {
    User user = user("family-devices@example.com");
    // Two independent devices, two live tokens in one family.
    RefreshService.TokenPair deviceA = refreshService.issue(user);
    RefreshService.TokenPair deviceB = refreshService.issue(user);

    // Device A logs out: only its row is revoked.
    refreshService.logout(deviceA.refreshToken());
    RefreshService.TokenPair deviceB2 = refreshService.rotate(deviceB.refreshToken());
    assertNotEquals(deviceB.refreshToken(), deviceB2.refreshToken(),
        "device B must keep rotating after A logs out");

    List<RefreshToken> stored = rowsOf(user.getId());
    RefreshToken a = stored.stream()
        .filter(t -> t.getTokenHash().equals(RefreshService.sha256(deviceA.refreshToken())))
        .findFirst().orElseThrow();
    assertTrue(a.isRevoked(), "logout must revoke the logged-out device's token");
    // A logged-out device's token is dead on its own: presenting it is a
    // refused replay (which then burns the family, the standard posture).
    assertThrows(BadCredentialsException.class,
        () -> refreshService.rotate(deviceA.refreshToken()),
        "a logged-out token must never mint a session");
  }

  // --- helpers ---------------------------------------------------------------

  private User user(String email) {
    return users.save(new User(email, "not-a-real-hash", "Family Lifecycle"));
  }

  private List<RefreshToken> rowsOf(UUID userId) {
    return refreshTokens.findAll().stream()
        .filter(t -> t.getUserId().equals(userId))
        .toList();
  }
}
