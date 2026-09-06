package com.bank.platform.security;

import com.bank.platform.common.ApiExceptionHandler;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In-memory token bucket guarding the auth endpoints against credential
 * stuffing and enumeration floods. Single-instance scope is fine for this
 * deployment shape; a Redis bucket would replace it behind a load balancer.
 *
 * <p>Client identity (F03): forwarding headers are TRUSTED ONLY when the
 * request's direct socket peer is inside the explicitly configured
 * {@code app.auth.rate-limit.trusted-proxies} CIDR allowlist (default: empty,
 * i.e. never). With no configured trusted edge, a spoofed
 * {@code X-Forwarded-For} cannot open a fresh bucket: the bucket key is the
 * socket address, so every value an attacker can mint maps to the same
 * budget. Behind an allowlisted edge that actually sanitizes forwarding
 * metadata (see docker-compose topology notes), the FIRST forwarded address
 * is the client; degenerate values (blank, oversized, malformed) fall back to
 * the socket address rather than minting attacker-chosen buckets.
 *
 * <p>Buckets live in a bounded, time-evicted cache (not an unbounded map): an
 * idle IP stops costing memory, and a flood of spoofed identities cannot
 * grow the table forever.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private static final int MAX_FORWARDED_LENGTH = 256;
  private static final String FORWARDED_FOR = "X-Forwarded-For";

  private final int perMinute;
  private final ObjectMapper objectMapper;
  private final Cache<String, Bucket> buckets;
  private final List<Cidr> trustedProxies;

  public RateLimitFilter(
      @Value("${app.auth.rate-limit.per-minute:20}") int perMinute,
      @Value("${app.auth.rate-limit.trusted-proxies:}") String trustedProxies,
      @Value("${app.auth.rate-limit.bucket-ttl-minutes:10}") long bucketTtlMinutes,
      ObjectMapper objectMapper) {
    this.perMinute = perMinute;
    this.objectMapper = objectMapper;
    this.trustedProxies = parseCidrs(trustedProxies);
    this.buckets = Caffeine.newBuilder()
        .expireAfterAccess(Duration.ofMinutes(bucketTtlMinutes))
        .maximumSize(100_000)
        .build();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register")
        || path.equals("/api/v1/auth/mfa/verify"));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = clientIp(request);
    Bucket bucket = buckets.get(key, ignored -> new Bucket(perMinute));
    if (!bucket.tryConsume()) {
      // Same RFC-7807 body the rest of the API answers with (type, title,
      // status, detail, timestamp) so the problem shape cannot drift between
      // this filter and the controllers.
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      response.setHeader("Retry-After", "60");
      objectMapper.writeValue(response.getWriter(),
          ApiExceptionHandler.body(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
              "Slow down and try again in a minute."));
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * The bucket key for one request: a canonical IP literal. Forwarded metadata
   * is only consulted when the direct peer is an allowlisted trusted proxy;
   * otherwise the socket address is authoritative (spoof-proof).
   */
  String clientIp(HttpServletRequest request) {
    String peer = request.getRemoteAddr();
    String canonicalPeer = canonicalIp(peer);
    if (canonicalPeer == null || !isTrustedPeer(canonicalPeer)) {
      return canonicalPeer == null ? peer : canonicalPeer;
    }
    String forwarded = request.getHeader(FORWARDED_FOR);
    if (forwarded == null || forwarded.isBlank() || forwarded.length() > MAX_FORWARDED_LENGTH) {
      return canonicalPeer;
    }
    String first = forwarded.split(",")[0].trim();
    String canonical = canonicalIp(first);
    return canonical == null ? canonicalPeer : canonical;
  }

  private boolean isTrustedPeer(String canonicalPeer) {
    try {
      InetAddress peer = InetAddress.getByName(canonicalPeer);
      for (Cidr cidr : trustedProxies) {
        if (cidr.contains(peer)) {
          return true;
        }
      }
    } catch (java.net.UnknownHostException ex) {
      return false;
    }
    return false;
  }

  /** Canonicalizes an IP literal, or null for malformed/oversized values. */
  private static String canonicalIp(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_FORWARDED_LENGTH) {
      return null;
    }
    String clean = value.trim();
    // A forwarding entry must be ONE literal - never a list, CIDR, or comment.
    if (clean.contains(",") || clean.contains("/") || clean.contains(" ")) {
      return null;
    }
    try {
      return InetAddress.getByName(clean).getHostAddress();
    } catch (java.net.UnknownHostException ex) {
      return null;
    }
  }

  private static List<Cidr> parseCidrs(String spec) {
    List<Cidr> parsed = new ArrayList<>();
    if (spec == null || spec.isBlank()) {
      return parsed;
    }
    for (String part : spec.split(",")) {
      String clean = part.trim();
      if (clean.isEmpty()) {
        continue;
      }
      try {
        parsed.add(Cidr.parse(clean));
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException(
            "Invalid CIDR in app.auth.rate-limit.trusted-proxies: " + clean, ex);
      }
    }
    return parsed;
  }

  /** Minimal IPv4/IPv6 CIDR containment (no external dependency needed). */
  private static final class Cidr {
    private final byte[] network;
    private final int prefix;

    static Cidr parse(String spec) {
      String[] parts = spec.split("/", 2);
      int prefix = parts.length == 2 ? Integer.parseInt(parts[1]) : 32;
      byte[] address;
      try {
        address = InetAddress.getByName(parts[0]).getAddress();
      } catch (java.net.UnknownHostException ex) {
        throw new IllegalArgumentException("Unparseable address: " + parts[0], ex);
      }
      int maxBits = address.length * 8;
      if (prefix < 0 || prefix > maxBits) {
        throw new IllegalArgumentException("Prefix out of range: " + prefix);
      }
      return new Cidr(address, prefix);
    }

    private Cidr(byte[] network, int prefix) {
      this.network = network;
      this.prefix = prefix;
    }

    boolean contains(InetAddress address) {
      byte[] candidate = address.getAddress();
      if (candidate.length != network.length) {
        return false;
      }
      int fullBytes = prefix / 8;
      int remainingBits = prefix % 8;
      for (int i = 0; i < fullBytes; i++) {
        if (candidate[i] != network[i]) {
          return false;
        }
      }
      if (remainingBits > 0) {
        int mask = 0xFF << (8 - remainingBits);
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
      }
      return true;
    }
  }

  private static final class Bucket {
    private final int capacity;
    private double tokens;
    private long updatedAt;

    Bucket(int capacity) {
      this.capacity = capacity;
      this.tokens = capacity;
      this.updatedAt = System.nanoTime();
    }

    synchronized boolean tryConsume() {
      long now = System.nanoTime();
      double elapsedMinutes = (now - updatedAt) / 60_000_000_000.0;
      tokens = Math.min(capacity, tokens + elapsedMinutes * capacity);
      updatedAt = now;
      if (tokens < 1) {
        return false;
      }
      tokens -= 1;
      return true;
    }
  }
}
