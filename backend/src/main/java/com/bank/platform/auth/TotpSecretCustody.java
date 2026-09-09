package com.bank.platform.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Custody boundary for TOTP seeds. When {@code app.totp.master-key} is
 * configured, a seed is never written to the database in recoverable form:
 * it is encrypted with AES-256-GCM under a key derived from the deployment
 * key, with a fresh random nonce per encryption and the owning user id bound
 * as additional authenticated data, so a ciphertext copied onto another
 * user's row cannot decrypt.
 *
 * <p>Stored payload format (versioned): {@code v1:<iv-b64>:<ciphertext-b64>}.
 * Version 0 rows in {@code users.totp_secret} are the legacy plaintext that
 * the startup migrator converts once a key is supplied. Without a configured
 * key this component is inactive and the simulator falls back to the legacy
 * plaintext column (local demo only); production configuration fails closed
 * in {@link com.bank.platform.common.DeploymentEnvGuard}.
 */
@Component
public class TotpSecretCustody {

  private static final int GCM_TAG_BITS = 128;
  private static final int IV_BYTES = 12;
  private static final int VERSION = 1;

  private final byte[] key;
  private final boolean active;
  private final SecureRandom random = new SecureRandom();

  public TotpSecretCustody(@Value("${app.totp.master-key:}") String masterKey) {
    boolean hasKey = masterKey != null && !masterKey.isBlank();
    this.key = hasKey ? sha256(masterKey) : new byte[0];
    this.active = hasKey;
  }

  /** True when the deployment supplies a master key (encryption is on). */
  public boolean isActive() {
    return active;
  }

  /** Encrypts a plaintext seed for the owning user; the caller stores the result. */
  public String encrypt(UUID userId, String plaintext) {
    requireActive();
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      byte[] iv = new byte[IV_BYTES];
      random.nextBytes(iv);
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return "v" + VERSION + ":"
          + Base64.getEncoder().encodeToString(iv) + ":"
          + Base64.getEncoder().encodeToString(encrypted);
    } catch (Exception ex) {
      throw new IllegalStateException("TOTP secret encryption failed", ex);
    }
  }

  /**
   * Decrypts a versioned payload for the owning user. A wrong master key, a
   * tampered ciphertext, or a payload bound to another user all fail loudly: * never returning a usable seed.
   */
  public String decrypt(UUID userId, String payload) {
    if (payload == null || payload.isBlank()) {
      return null;
    }
    try {
      String[] parts = payload.split(":", 3);
      if (parts.length != 3 || !("v" + VERSION).equals(parts[0])) {
        throw new IllegalArgumentException("Unsupported TOTP custody payload version");
      }
      byte[] iv = Base64.getDecoder().decode(parts[1]);
      byte[] encrypted = Base64.getDecoder().decode(parts[2]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_BITS, iv));
      cipher.updateAAD(userId.toString().getBytes(StandardCharsets.UTF_8));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException ex) {
      throw ex;
    } catch (Exception ex) {
      // AEADBadTagException (wrong key / tampering) must surface as a loud
      // configuration failure, not as an absent secret.
      throw new IllegalStateException(
          "TOTP secret decryption failed: is APP_TOTP_MASTER_KEY correct?", ex);
    }
  }

  private void requireActive() {
    if (!active) {
      throw new IllegalStateException(
          "TOTP secret encryption requested without a configured master key");
    }
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
