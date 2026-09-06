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

/**
 * Token minting and - critically - token PURPOSE separation.
 *
 * <p>Every token this service mints carries a {@code purpose} claim plus a
 * purpose-scoped audience. Validation is typed: {@link #parseAccess} and
 * {@link #requireMfaSubject} each require BOTH their own purpose AND their own
 * audience, so a correctly signed MFA challenge can never authenticate a
 * protected request and an access token can never complete an MFA challenge.
 * There is deliberately no generic "subject extractor": downstream code cannot
 * accidentally accept a challenge token as an authenticated identity.
 */
@Service
public class JwtService {

  private static final String ISSUER = Brand.JWT_ISSUER;
  private static final String ACCESS_AUDIENCE = Brand.JWT_AUDIENCE;
  private static final String MFA_AUDIENCE = Brand.JWT_MFA_AUDIENCE;
  private static final String ACCESS_PURPOSE = "access";
  private static final String MFA_PURPOSE = "mfa";
  // Approved signing algorithm (RFC 8725 section 3.1: pin the algorithm family). jjwt
  // will verify any HMAC that fits the key material, so accepting the default
  // would let an HS384-signed token in even though we only ever mint HS256.
  private static final String ALGORITHM = "HS256";

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

  /** Mints an ACCESS token: authenticates protected requests and nothing else. */
  public String generate(String email, String role, int securityVersion) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .audience().add(ACCESS_AUDIENCE).and()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("purpose", ACCESS_PURPOSE)
        // Role stays a server-side hint for logs/OpenAPI; authorities are
        // always resolved from the database at authentication time.
        .claim("role", role)
        // The security version under which this session was minted (F02): the
        // auth filter rejects a token whose version no longer matches the
        // user's row, so a factor change revokes old access tokens immediately.
        .claim("sv", securityVersion)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(accessSeconds)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * The one access-token validation path. Everything the JwtAuthFilter trusts
   * about a presented bearer token comes out of this typed result - a caller
   * cannot build a principal from any token that was not minted for access.
   */
  public record AccessToken(String subject, int securityVersion) {}

  public AccessToken parseAccess(String token) {
    Claims claims = parseTyped(token, ACCESS_PURPOSE, ACCESS_AUDIENCE);
    Integer sv = claims.get("sv", Integer.class);
    if (sv == null) {
      throw new JwtException("Access token carries no security version");
    }
    return new AccessToken(claims.getSubject(), sv);
  }

  /** Short-lived login-challenge token. Verified by purpose, never accepted as auth. */
  public String generateMfa(String email) {
    Instant now = Instant.now();
    return Jwts.builder()
        .issuer(ISSUER)
        .audience().add(MFA_AUDIENCE).and()
        .subject(email)
        .id(UUID.randomUUID().toString())
        .claim("purpose", MFA_PURPOSE)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plusSeconds(5 * 60)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * The one MFA-challenge validation path: requires the challenge purpose AND
   * the challenge audience, so an access token (or any other signed token)
   * fails here. Subject = the email the challenge was minted for.
   */
  public String requireMfaSubject(String token) {
    Claims claims = parseTyped(token, MFA_PURPOSE, MFA_AUDIENCE);
    return claims.getSubject();
  }

  /**
   * Signature, algorithm, issuer, purpose and audience must ALL match before
   * any claim is trusted. jjwt validates signature + exp/not-before on parse;
   * the remaining claims are checked here so a token signed with the right key
   * but the wrong purpose/audience/issuer/algorithm is rejected, never passed
   * up as merely "signed".
   */
  private Claims parseTyped(String token, String expectedPurpose, String expectedAudience) {
    var jws = Jwts.parser()
        .verifyWith(key)
        .build()
        .parseSignedClaims(token);
    Claims claims = jws.getPayload();
    if (!ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
      throw new JwtException("Token uses an unapproved algorithm");
    }
    if (!ISSUER.equals(claims.getIssuer())) {
      throw new JwtException("Token has an unexpected issuer");
    }
    if (claims.getAudience() == null || !claims.getAudience().contains(expectedAudience)) {
      throw new JwtException("Token has an unexpected audience");
    }
    if (!expectedPurpose.equals(claims.get("purpose", String.class))) {
      throw new JwtException("Token does not carry the required purpose");
    }
    return claims;
  }

  /** Effective access-token lifetime in seconds (the e2e override may shorten it). */
  public long getAccessSeconds() {
    return accessSeconds;
  }
}
