package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/**
 * The production env guard must refuse to boot with any documented dev
 * default credential, and pass when every credential is overridden.
 */
class DeploymentEnvGuardTest {

  private static final String DEV_JWT =
      "dev-only-change-me-0123456789abcdef-0123456789abcdef-0123456789abcdef";
  private static final String DEV_ADMIN = "change-me-admin-123";
  private static final String DEV_DB = "bankapp_secret_change_me";

  private ApplicationArguments noArgs() {
    return new org.springframework.boot.DefaultApplicationArguments(new String[0]);
  }

  @Test
  void productionRefusesDevDefaults() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard("production", DEV_JWT, DEV_ADMIN, DEV_DB);
    assertThrows(IllegalStateException.class, () -> guard.run(noArgs()));
  }

  @Test
  void productionRefusesAnySingleDevDefault() {
    DeploymentEnvGuard devJwt = new DeploymentEnvGuard("production", DEV_JWT, "real-admin-pass", "real-db-pass");
    assertThrows(IllegalStateException.class, () -> devJwt.run(noArgs()));

    DeploymentEnvGuard devAdmin = new DeploymentEnvGuard("production", "real-jwt-secret-0123456789abcdef-0123456789abcdef", DEV_ADMIN, "real-db-pass");
    assertThrows(IllegalStateException.class, () -> devAdmin.run(noArgs()));

    DeploymentEnvGuard devDb = new DeploymentEnvGuard("production", "real-jwt-secret-0123456789abcdef-0123456789abcdef", "real-admin-pass", DEV_DB);
    assertThrows(IllegalStateException.class, () -> devDb.run(noArgs()));
  }

  @Test
  void productionPassesWhenEveryCredentialIsOverridden() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard(
        "production", "real-jwt-secret-0123456789abcdef-0123456789abcdef", "real-admin-pass", "real-db-pass");
    assertDoesNotThrow(() -> guard.run(noArgs()));
  }

  @Test
  void devModeOnlyWarnsAndNeverFails() {
    DeploymentEnvGuard guard = new DeploymentEnvGuard("dev", DEV_JWT, DEV_ADMIN, DEV_DB);
    assertDoesNotThrow(() -> guard.run(noArgs()));
  }
}
