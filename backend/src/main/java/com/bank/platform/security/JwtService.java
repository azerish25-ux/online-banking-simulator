package com.bank.platform.security;

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

  private final SecretKey key;
  private final long accessMinutes;

  public JwtService(
      @Value("${app.jwt.secret}") String secret,
      @Value("${app.jwt.access-minutes:15}") long accessMinutes) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessMinutes = accessMinutes;
  }

  public String generate(String email, String role) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("role", role)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(accessMinutes * 60)))
        .signWith(key)
        .compact();
  }

  public String extractEmail(String token) {

    return Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .getSubject();
  }
  /** Short-lived login-challenge token. Verified by purpose, never accepted as auth. */
  public String generateMfa(String email) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("purpose", "mfa")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(5 * 60)))
        .signWith(key)
        .compact();
  }

  public String requireMfaSubject(String token) {
    var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    if (!"mfa".equals(claims.get("purpose", String.class))) {
      throw new io.jsonwebtoken.JwtException("Not an MFA token");
    }
    return claims.getSubject();
  }

  public long getAccessMinutes() {
    return accessMinutes;
  }
}
