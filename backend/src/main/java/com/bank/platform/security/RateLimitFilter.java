package com.bank.platform.security;

import com.bank.platform.common.ApiExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
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
 * Buckets live in a bounded, time-evicted cache (not an unbounded map): an
 * idle IP stops costing memory, and a flood of spoofed identities cannot
 * grow the table forever.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private final int perMinute;
  private final boolean trustProxyHeaders;
  private final ObjectMapper objectMapper;
  private final Cache<String, Bucket> buckets;

  public RateLimitFilter(
      @Value("${app.auth.rate-limit.per-minute:20}") int perMinute,
      @Value("${app.trust-proxy-headers:false}") boolean trustProxyHeaders,
      @Value("${app.auth.rate-limit.bucket-ttl-minutes:10}") long bucketTtlMinutes,
      ObjectMapper objectMapper) {
    this.perMinute = perMinute;
    this.objectMapper = objectMapper;
    this.trustProxyHeaders = trustProxyHeaders;
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
   * The bucket key for one request.
   *
   * Proxy trust model: {@code X-Forwarded-For} is spoofable by any client that
   * can reach us directly, so it is ignored unless a proxy we control sits in
   * front of every request (compose topology). That proxy must REPLACE the
   * header with the real peer address - Next.js rewrites do this from the
   * socket peer (vercel/next.js#57397). With such a proxy the leftmost entry
   * is the client. Degenerate values (blank, oversized garbage) fall back to
   * the socket address rather than minting a fresh bucket.
   */
  String clientIp(HttpServletRequest request) {
    if (!trustProxyHeaders) {
      return request.getRemoteAddr();
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank() && forwarded.length() <= 256) {
      String first = forwarded.split(",")[0].trim();
      if (!first.isEmpty()) {
        return first;
      }
    }
    return request.getRemoteAddr();
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
