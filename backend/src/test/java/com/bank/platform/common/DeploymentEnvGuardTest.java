package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/**
 * The production env guard must refuse to boot with any documented dev
 * default credential (including a missing/placeholder TOTP master key, F30),
 * and pass when every credential is overridden.
 */
class DeploymentEnvGuardTest {

  private static final String DEV_JWT =
      "dev-only-change-me-0123456789abcdef-0123456789abcdef-0123456789abcdef";
  private static final String DEV_ADMIN = "change-me-admin-123";
  private static final String DEV_DB = "bankapp_secret_change_me";
  private static final String DEV_TOTP = "dev-totp-master-key-change-me";
  private static final String REAL_JWT = "real-jwt-secret-0123456789abcdef-0123456789abcdef";
  private static final String REAL_TOTP = "a-real-totp-master-key-with-entropy-0123456789";

  private ApplicationArguments noArgs() {
    return new org.springframework.boot.DefaultApplicationArguments(new String[0]);
  }

  @Test
  void productionRefusesDevDefaults() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard("production", DEV_JWT, DEV_ADMIN, DEV_DB, DEV_TOTP);
    assertThrows(IllegalStateException.class, () -> guard.run(noArgs()));
  }

  @Test
  void productionRefusesAnySingleDevDefault() {
    DeploymentEnvGuard devJwt = new DeploymentEnvGuard("production", DEV_JWT, "real-admin-pass", "real-db-pass", REAL_TOTP);
    assertThrows(IllegalStateException.class, () -> devJwt.run(noArgs()));

    DeploymentEnvGuard devAdmin = new DeploymentEnvGuard("production", REAL_JWT, DEV_ADMIN, "real-db-pass", REAL_TOTP);
    assertThrows(IllegalStateException.class, () -> devAdmin.run(noArgs()));

    DeploymentEnvGuard devDb = new DeploymentEnvGuard("production", REAL_JWT, "real-admin-pass", DEV_DB, REAL_TOTP);
    assertThrows(IllegalStateException.class, () -> devDb.run(noArgs()));
  }

  @Test
  void productionRefusesMissingOrPlaceholderTotpMasterKey() {
    // Missing key: F30 fail-closed - seeds must never silently fall back to
    // plaintext at rest in a production deployment.
    DeploymentEnvGuard missing = new DeploymentEnvGuard("production", REAL_JWT, "real-admin-pass", "real-db-pass", "");
    assertThrows(IllegalStateException.class, () -> missing.run(noArgs()));

    DeploymentEnvGuard placeholder =
        new DeploymentEnvGuard("production", REAL_JWT, "real-admin-pass", "real-db-pass", DEV_TOTP);
    assertThrows(IllegalStateException.class, () -> placeholder.run(noArgs()));
  }

  @Test
  void productionPassesWhenEveryCredentialIsOverridden() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard(
        "production", REAL_JWT, "real-admin-pass", "real-db-pass", REAL_TOTP);
    assertDoesNotThrow(() -> guard.run(noArgs()));
  }

  @Test
  void devModeOnlyWarnsAndNeverFails() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard("dev", DEV_JWT, DEV_ADMIN, DEV_DB, "");
    assertDoesNotThrow(() -> guard.run(noArgs()));
  }
}
