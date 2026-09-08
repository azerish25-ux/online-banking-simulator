package com.bank.platform.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A pending TOTP enrollment. Starting setup creates a pending row with a
 * NEW secret and NEVER touches the active factor. The row expires and is
 * promoted only after the new authenticator verifies; cancelling or
 * abandoning it leaves the old factor untouched. Secrets travel through the
 * same custody boundary as active seeds (version 0 = legacy plaintext in a
 * keyless local demo).
 */
@Entity
@Table(name = "totp_enrollments")
public class TotpEnrollment {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "pending_secret", length = 512)
  private String pendingSecret;

  @Column(name = "pending_key_version", nullable = false)
  private int pendingKeyVersion;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  private boolean consumed;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TotpEnrollment() {}

  public TotpEnrollment(UUID userId, String storedSecret, int keyVersion, Instant expiresAt) {
    this.userId = userId;
    this.pendingSecret = storedSecret;
    this.pendingKeyVersion = keyVersion;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getPendingSecret() { return pendingSecret; }
  public int getPendingKeyVersion() { return pendingKeyVersion; }
  public Instant getExpiresAt() { return expiresAt; }
  public boolean isConsumed() { return consumed; }
  public void setConsumed(boolean v) { consumed = v; }
  public Instant getCreatedAt() { return createdAt; }
}
