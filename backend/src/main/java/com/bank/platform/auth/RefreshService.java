package com.bank.platform.auth;

import com.bank.platform.security.JwtService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshService {

  public record TokenPair(String accessToken, String refreshToken, long expiresInSeconds, User user) {}

  public static final String COOKIE = "refresh_token";
  // Backwards-compatible default only: the LIVE cookie max age always comes
  // from cookieMaxAgeSeconds() so a configured AUTH_REFRESH_DAYS is honored by
  // both the stored token and the browser cookie.
  public static final long COOKIE_MAX_AGE = 7 * 24 * 3600L;

  private final RefreshTokenRepository refreshTokens;
  private final UserRepository users;
  private final JwtService jwtService;
  private final SecureRandom random = new SecureRandom();
  private final long refreshDays;

  @PersistenceContext
  private EntityManager entityManager;

  public RefreshService(
      RefreshTokenRepository refreshTokens,
      UserRepository users,
      JwtService jwtService,
      @Value("${app.auth.refresh-days:7}") long refreshDays) {
    this.refreshTokens = refreshTokens;
    this.users = users;
    this.jwtService = jwtService;
    this.refreshDays = refreshDays;
  }

  /**
   * Browser-cookie lifetime for the refresh token. Derived from the SAME
   * configured refresh-days value that sets the token's DB expiry, so the
   * cookie never outlives (or underlives) the credential it carries.
   */
  public long cookieMaxAgeSeconds() {
    return refreshDays * 24 * 3600L;
  }

  /**
   * The authoritative credential-issuance boundary. The caller supplies the
   * user snapshot its proof was validated against; this boundary re-reads the
   * user UNDER the per-user lock, refuses a snapshot whose security epoch is
   * no longer current, and only then mints the credential pair bound to that
   * epoch. A paused login that verified its proof before a factor change
   * therefore cannot insert a live refresh row after the revocation commit.
   *
   * <p>Callers run this in their own transaction; the lock and the insert
   * commit atomically with the proof validation. The returned user is the
   * locked, current row — never the caller's snapshot.
   */
  @Transactional
  public TokenPair issue(User provenUser) {
    User current = users.findByIdForUpdate(provenUser.getId())
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    if (current.getSecurityVersion() != provenUser.getSecurityVersion()) {
      // The proof was validated against an epoch that no longer exists (a
      // factor change committed between proof and issuance). Minting now
      // would let rotation upgrade stale authentication, so nothing is
      // issued and nothing is inserted.
      throw new BadCredentialsException(
          "Authentication proof no longer matches the current security state");
    }
    return issue(current, current.getSecurityVersion());
  }

  /**
   * The epoch-bound mint, callable only with the CURRENT epoch (callers hold
   * the per-user lock or the epoch has just been verified). Callers that
   * moved security state in this same transaction pass the new epoch so the
   * new credentials are born under the state they just created.
   */
  public TokenPair issue(User current, int securityVersion) {
    if (current.getSecurityVersion() != securityVersion) {
      throw new BadCredentialsException(
          "Credential epoch does not match the user's current security state");
    }
    // Opportunistic housekeeping on every mint keeps the table bounded: rows
    // only leave once they are past their expiry (a revoked-but-unexpired
    // token must still be findable so replaying it burns its family).
    refreshTokens.purgeExpired(Instant.now());
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    RefreshToken stored = new RefreshToken(
        current.getId(), sha256(plain), Instant.now().plusSeconds(refreshDays * 24 * 3600));
    stored.setSecurityVersion(securityVersion);
    refreshTokens.save(stored);
    String access = jwtService.generate(
        current.getEmail(), current.getRole().name(), securityVersion);
    return new TokenPair(access, plain, jwtService.getAccessSeconds(), current);
  }

  /**
   * The 401 is signalled by throwing BadCredentialsException, but the state
   * changes made before it (consuming the token, burning the family) are
   * deliberate and must commit: otherwise a RuntimeException would roll them
   * back and a burned family would resurrect on the next replay.
   */
  @Transactional(noRollbackFor = BadCredentialsException.class)
  public TokenPair rotate(String presented) {
    String hash = sha256(presented);
    RefreshToken found = refreshTokens.findByTokenHash(hash).orElse(null);
    // Bulk revocations bypass the persistence context: reload the row so a
    // concurrently-revoked token can never read as valid.
    if (found != null) {
      entityManager.refresh(found);
    }
    if (found == null || found.isRevoked() || found.getExpiresAt().isBefore(Instant.now())) {
      if (found != null) {
        // Reused or expired token: assume theft, burn the whole family.
        refreshTokens.revokeAllByUserId(found.getUserId());
      }
      throw new BadCredentialsException("Invalid refresh token");
    }
    // Serialize rotations per user before consuming. Two threads presenting
    // the SAME token queue on this row lock: the winner consumes and mints its
    // successor, and only after that commit does the loser's consume return 0,
    // at which point the family burn also revokes the winner's new token.
    // Without the lock the loser could burn the family before the winner's
    // successor row commits, leaving a live session behind.
    users.findByIdForUpdate(found.getUserId())
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    // Check-and-consume in one UPDATE: two parallel rotations presenting the
    // same token cannot both pass an in-Java revoked check and mint separate
    // sessions: the loser is treated as a replay and the family is burned.
    if (refreshTokens.consume(hash) == 0) {
      refreshTokens.revokeAllByUserId(found.getUserId());
      throw new BadCredentialsException("Invalid refresh token");
    }
    // consume() bypassed the persistence context: re-read the row so the
    // managed copy reflects the revoked state we just wrote.
    entityManager.refresh(found);
    User user = users.findById(found.getUserId())
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    // The presented token's epoch must still be current: a credential minted
    // before a factor change is dead even if its row escaped the revocation
    // (the paused-login interleaving this bound exists to close).
    if (user.getSecurityVersion() != found.getSecurityVersion()) {
      throw new BadCredentialsException(
          "This session predates a security change; sign in again");
    }
    return issue(user, user.getSecurityVersion());
  }

  @Transactional
  public void logout(String presented) {
    if (presented == null) {
      return;
    }
    refreshTokens.findByTokenHash(sha256(presented)).ifPresent(token -> {
      token.setRevoked(true);
      refreshTokens.save(token);
    });
  }

  static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
