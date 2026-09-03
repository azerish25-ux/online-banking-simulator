package com.bank.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In-memory token bucket guarding the auth endpoints against credential
 * stuffing and enumeration floods. Single-instance scope is fine for this
 * deployment shape; a Redis bucket would replace it behind a load balancer.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

  private final int perMinute;
  private final ObjectMapper objectMapper;
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  public RateLimitFilter(
      @Value("${app.auth.rate-limit.per-minute:20}") int perMinute, ObjectMapper objectMapper) {
    this.perMinute = perMinute;
    this.objectMapper = objectMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return !(path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/register"));
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String key = clientIp(request);
    Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(perMinute));
    if (!bucket.tryConsume()) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.setHeader("Retry-After", "60");
      objectMapper.writeValue(response.getWriter(), Map.of(
          "type", "https://bank.local/problems/429",
          "title", "Too Many Requests",
          "status", 429,
          "detail", "Slow down and try again in a minute.",
          "timestamp", Instant.now().toString()));
      return;
    }
    chain.doFilter(request, response);
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
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
