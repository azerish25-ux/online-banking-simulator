package com.bank.platform.common;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Deployment guard: local demos run on the built-in dev-only defaults (loudly
 * warned about), but a production deployment must override every credential -
 * the JWT signing secret, the seeded admin password, and the database
 * password. When {@code app.deployment-env=production} is set and any of the
 * known dev defaults is still in effect, refuse to start instead of shipping
 * a bank with publicly documented credentials.
 *
 * The default strings mirror application.yml / docker-compose.yml; if those
 * change, update this class in the same commit.
 */
@Component
public class DeploymentEnvGuard implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DeploymentEnvGuard.class);

  private final String deploymentEnv;
  private final Map<String, String> devDefaults = new LinkedHashMap<>();
  private final String totpMasterKey;

  public DeploymentEnvGuard(
      @Value("${app.deployment-env:dev}") String deploymentEnv,
      @Value("${app.jwt.secret}") String jwtSecret,
      @Value("${app.admin.password:change-me-admin-123}") String adminPassword,
      @Value("${spring.datasource.password:}") String dbPassword,
      @Value("${app.totp.master-key:}") String totpMasterKey) {
    this.deploymentEnv = deploymentEnv == null ? "dev" : deploymentEnv.trim().toLowerCase();
    this.totpMasterKey = totpMasterKey == null ? "" : totpMasterKey.trim();
    // Keys are the env vars operators override; values are the documented dev defaults.
    devDefaults.put("JWT_SECRET", jwtSecret);
    devDefaults.put("APP_ADMIN_PASSWORD", adminPassword);
    devDefaults.put("PG_PASSWORD", dbPassword);
  }

  @Override
  public void run(ApplicationArguments args) {
    if ("production".equals(deploymentEnv)) {
      String devJwt = "dev-only-change-me-0123456789abcdef-0123456789abcdef-0123456789abcdef";
      String composeJwt = "change-me-in-production-0123456789abcdef-0123456789abcdef";
      String devAdmin = "change-me-admin-123";
      String devDb = "bankapp_secret_change_me";
      String devTotpKey = "dev-totp-master-key-change-me";

      Map<String, Boolean> insecure = new LinkedHashMap<>();
      insecure.put("JWT_SECRET (dev default)", devDefaults.get("JWT_SECRET").equals(devJwt));
      insecure.put("JWT_SECRET (compose default)", devDefaults.get("JWT_SECRET").equals(composeJwt));
      insecure.put("APP_ADMIN_PASSWORD", devDefaults.get("APP_ADMIN_PASSWORD").equals(devAdmin));
      insecure.put("PG_PASSWORD", devDefaults.get("PG_PASSWORD").equals(devDb));
      // F30 fail-closed: production must supply a real TOTP master key, and a
      // missing or placeholder value is a startup error, never a silent fall
      // back to plaintext-at-rest seeds.
      insecure.put("APP_TOTP_MASTER_KEY (missing or placeholder)",
          totpMasterKey.isEmpty() || devTotpKey.equals(totpMasterKey));

      String offenders = insecure.entrySet().stream()
          .filter(Map.Entry::getValue)
          .map(Map.Entry::getKey)
          .reduce((a, b) -> a + ", " + b)
          .orElse(null);
      if (offenders != null) {
        throw new IllegalStateException(
            "Refusing to start in production: still using the dev default for " + offenders
                + ". Override them in the environment (see README / docker-compose.yml).");
      }
      log.info("Production environment: all credentials overridden from their dev defaults.");
      return;
    }
    boolean anyDevDefault = devDefaults.values().stream().anyMatch(value ->
        value.equals("change-me-admin-123")
            || value.equals("bankapp_secret_change_me")
            || value.startsWith("dev-only-change-me")
            || value.startsWith("change-me-in-production-"));
    if (anyDevDefault) {
      log.warn("Dev-only credentials in use (JWT/admin/DB). Set APP_ADMIN_PASSWORD, JWT_SECRET "
          + "and PG_PASSWORD for anything beyond a local demo, and app.deployment-env=production "
          + "to make the guard refuse these defaults.");
    }
    if (totpMasterKey.isEmpty()) {
      log.warn("TOTP secrets are stored WITHOUT encryption (no APP_TOTP_MASTER_KEY). "
          + "Set the key for anything beyond a local demo; production refuses to start without it.");
    }
  }
}
