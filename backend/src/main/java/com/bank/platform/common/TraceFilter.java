package com.bank.platform.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates {@code X-Request-Id} (or mints one) so every log line and every
 * error response for a request can be correlated. Runs first in the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

  public static final String TRACE_ID = "traceId";
  public static final String HEADER = "X-Request-Id";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = request.getHeader(HEADER);
    if (traceId == null || traceId.isBlank() || traceId.length() > 64) {
      traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    MDC.put(TRACE_ID, traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }
}
