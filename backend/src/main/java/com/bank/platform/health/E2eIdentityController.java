package com.bank.platform.health;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run-specific identity endpoint for the browser suite's preflight.
 * A health body and CSP header do not identify the actual
 * database or source build, so before ANY seeding or destructive browser
 * step the suite asks THIS endpoint to name itself.
 *
 * <p>The endpoint is NOT a public production control: it answers only when
 * the process was booted with a non-empty {@code E2E_MARKER} environment
 * value AND the probe presents that exact value in {@code X-E2E-Marker}.
 * Production (no marker) gets 404 - the route is indistinguishable from a
 * missing control - and a probe carrying a stale or foreign marker gets 403
 * before it can learn anything about the database. Each run boots its backend
 * with its own marker, so a leftover server from an earlier run (the
 * documented two-deposit incident) is refused BEFORE the first write instead
 * of passing a vague health check.
 *
 * <p>The body names the build (backend version) and the ACTUAL database
 * connection (product, product version, JDBC URL and role) read from live
 * JDBC metadata - never from an environment variable named TEST. The suite
 * asserts the marker round-trips and, when configured, that the database is
 * the disposable one it expects.
 */
@RestController
@RequestMapping("/api/e2e")
public class E2eIdentityController {

  private static final Logger log = LoggerFactory.getLogger(E2eIdentityController.class);

  private final Environment environment;
  private final DataSource dataSource;
  private final String version;

  public E2eIdentityController(Environment environment, DataSource dataSource,
      @Value("${info.app.version:unknown}") String version) {
    this.environment = environment;
    this.dataSource = dataSource;
    this.version = version;
  }

  /** The marker this process was booted with (empty = not an e2e run). */
  String configuredMarker() {
    String fromEnv = environment.getProperty("E2E_MARKER", "");
    if (!fromEnv.isBlank()) {
      return fromEnv;
    }
    return environment.getProperty("app.e2e.marker", "");
  }

  @GetMapping("/identity")
  public ResponseEntity<Map<String, Object>> identity(
      @RequestHeader(name = "X-E2E-Marker", required = false) String presentedMarker) {
    String marker = configuredMarker();
    if (marker.isBlank()) {
      // Not an e2e run: this control does not exist. 404 keeps it invisible
      // to production scanners (never a public "test mode is on" beacon).
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }
    if (!marker.equals(presentedMarker)) {
      // A probe from a different run (stale backend? wrong stack?) learns
      // nothing: 403 with no body details.
      log.warn("E2E identity probe refused: presented marker does not match this run");
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("marker", marker);
    body.put("testMode", true);
    body.put("version", version);
    try (Connection connection = dataSource.getConnection()) {
      DatabaseMetaData meta = connection.getMetaData();
      body.put("db", Map.of(
          "product", String.valueOf(meta.getDatabaseProductName()),
          "productVersion", String.valueOf(meta.getDatabaseProductVersion()),
          "url", String.valueOf(meta.getURL()),
          "user", String.valueOf(meta.getUserName())));
    } catch (Exception ex) {
      // Never let an identity probe 500 - name the failure so the suite can
      // refuse the target, but no internals leak to the response.
      log.error("E2E identity probe could not read database metadata", ex);
      body.put("db", Map.of("unavailable", "metadata read failed"));
    }
    return ResponseEntity.ok(body);
  }
}
