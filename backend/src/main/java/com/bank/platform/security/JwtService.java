package com.bank.platform.security;

import com.bank.platform.common.Brand;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

  private static final String ISSUER = Brand.JWT_ISSUER;
  private static final String AUDIENCE = Brand.JWT_AUDIENCE;

  private final SecretKey key;
  private final long accessSeconds;

  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-minutes:15}") long accessMinutes,
      @Value("${app.jwt.access-seconds:0}") long accessSeconds) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    // E2E override: the browser suite boots the jar with a seconds-scale TTL
    // so it can prove silent refresh across a real expiry without waiting
    // 15 minutes. When set, it wins over access-minutes.
    this.accessSeconds = accessSeconds > 0 ? accessSeconds : accessMinutes * 60;
  }

  public String generate(String email, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .audience().add(AUDIENCE).and()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(accessSeconds)))
        .signWith(key)
        .compact();
  }

  public String extractEmail(String token) {
    return parse(token).getSubject();
  }

  /** Short-lived login-challenge token. Verified by purpose, never accepted as auth. */
  public String generateMfa(String email) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .audience().add(AUDIENCE).and()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("purpose", "mfa")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(5 * 60)))
        .signWith(key)
        .compact();
  }

  public String requireMfaSubject(String token) {
    Claims claims = parse(token);
    if (!"mfa".equals(claims.get("purpose", String.class))) {
      throw new JwtException("Not an MFA token");
    }
    return claims.getSubject();
  }

  /** Every token minted by this service carries issuer + audience; enforce both on parse. */
  private Claims parse(String token) {
    Claims claims = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload();
    if (!ISSUER.equals(claims.getIssuer())
        || claims.getAudience() == null
        || !claims.getAudience().contains(AUDIENCE)) {
      throw new JwtException("Token has unexpected issuer or audience");
    }
    return claims;
  }

  /** Effective access-token lifetime in seconds (the e2e override may shorten it). */
  public long getAccessSeconds() {
    return accessSeconds;
  }
}
