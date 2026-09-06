package com.bank.platform.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * F30 forward migration for secrets that predate custody. When a master key is
 * configured, every legacy version-0 plaintext row is encrypted in place on
 * startup and marked version 1. Idempotent and reversible with the same key;
 * it never runs when no key is configured (local plaintext demos stay as-is).
 */
@Component
public class TotpLegacySecretMigrator implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TotpLegacySecretMigrator.class);

  private final UserRepository users;
  private final TotpSecretCustody custody;

  public TotpLegacySecretMigrator(UserRepository users, TotpSecretCustody custody) {
    this.users = users;
    this.custody = custody;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!custody.isActive()) {
      return;
    }
    java.util.List<User> legacy = users.findByTotpKeyVersionAndTotpSecretIsNotNull(0);
    if (legacy.isEmpty()) {
      return;
    }
    for (User user : legacy) {
      String plaintext = user.getTotpSecret();
      user.setTotpSecretCiphertext(custody.encrypt(user.getId(), plaintext));
      user.setTotpKeyVersion(1);
      user.setTotpSecret(null);
      users.save(user);
    }
    log.info("Encrypted {} legacy TOTP secret(s) under the configured master key.", legacy.size());
  }
}
