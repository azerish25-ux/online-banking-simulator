package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * custody boundary: with a master key the stored payload never contains
 * the seed, round-trips through AES-256-GCM, and cannot be decrypted with a
 * wrong key, a tampered payload, or under another user's identity (AAD).
 */
class TotpCustodyTest {

  private static final UUID USER = UUID.randomUUID();
  private static final String KEY = "test-totp-master-key-with-enough-entropy-0123456789";

  @Test
  void roundTripPreservesSecretAndDoesNotLeakIt() {
    TotpSecretCustody custody = new TotpSecretCustody(KEY);
    String payload = custody.encrypt(USER, "JBSWY3DPEHPK3PXP");
    assertFalse(payload.contains("JBSWY3DPEHPK3PXP"), "ciphertext must not contain the seed");
    assertEquals("JBSWY3DPEHPK3PXP", custody.decrypt(USER, payload));
  }

  @Test
  void wrongKeyFailsLoudly() {
    TotpSecretCustody custody = new TotpSecretCustody(KEY);
    String payload = custody.encrypt(USER, "JBSWY3DPEHPK3PXP");
    TotpSecretCustody wrongKey = new TotpSecretCustody("some-other-master-key-entropy-0123456789");
    assertThrows(IllegalStateException.class, () -> wrongKey.decrypt(USER, payload));
  }

  @Test
  void payloadIsBoundToTheOwningUser() {
    TotpSecretCustody custody = new TotpSecretCustody(KEY);
    String payload = custody.encrypt(USER, "JBSWY3DPEHPK3PXP");
    // Same ciphertext under another user's id must fail authentication.
    assertThrows(IllegalStateException.class,
        () -> custody.decrypt(UUID.randomUUID(), payload));
  }

  @Test
  void tamperedPayloadFailsLoudly() {
    TotpSecretCustody custody = new TotpSecretCustody(KEY);
    String payload = custody.encrypt(USER, "JBSWY3DPEHPK3PXP");
    String tampered = payload.substring(0, payload.length() - 2) + "AA";
    assertThrows(IllegalStateException.class, () -> custody.decrypt(USER, tampered));
  }

  @Test
  void everyEncryptionUsesAFreshNonce() {
    TotpSecretCustody custody = new TotpSecretCustody(KEY);
    assertNotEquals(custody.encrypt(USER, "JBSWY3DPEHPK3PXP"),
        custody.encrypt(USER, "JBSWY3DPEHPK3PXP"),
        "identical plaintext must never produce identical ciphertext");
  }

  @Test
  void inactiveWithoutKeyAndEncryptionRefused() {
    TotpSecretCustody custody = new TotpSecretCustody("  ");
    assertFalse(custody.isActive());
    assertThrows(IllegalStateException.class, () -> custody.encrypt(USER, "JBSWY3DPEHPK3PXP"));
    // Decrypt of nothing is simply absent.
    assertEquals(null, custody.decrypt(USER, null));
    assertTrue(new TotpSecretCustody(KEY).isActive());
  }
}
