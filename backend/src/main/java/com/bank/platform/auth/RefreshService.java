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

  @Transactional
  public TokenPair issue(User user) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String plain = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    refreshTokens.save(new RefreshToken(
        user.getId(), sha256(plain), Instant.now().plusSeconds(refreshDays * 24 * 3600)));
    String access = jwtService.generate(user.getEmail(), user.getRole().name());
    return new TokenPair(access, plain, jwtService.getAccessMinutes() * 60, user);
  }

  @Transactional
  public TokenPair rotate(String presented) {
    RefreshToken found = refreshTokens.findByTokenHash(sha256(presented)).orElse(null);
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
    found.setRevoked(true);
    refreshTokens.save(found);
    User user = users.findById(found.getUserId())
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    return issue(user);
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
