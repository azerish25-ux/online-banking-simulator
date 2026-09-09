package com.bank.platform.health;

import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final String version;

  public HealthController(@Value("${info.app.version:unknown}") String version) {
    this.version = version;
  }

  /**
   * Canonical liveness endpoint (GET /api/health). Reachable without auth so
   * the Docker healthcheck, the dev scripts and CI can poll it. There is no
   * /api/v1/health twin: a duplicate route would only invite drift.
   */
  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of(
        "status", "UP",
        "service", "bank-platform",
        "version", version,
        "timestamp", Instant.now().toString());
  }
}
