package com.bank.platform.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, unique = true, length = 64)
  private String tokenHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  private boolean revoked;

  /**
   * The security epoch this credential was authenticated under (the user's
   * security_version at mint time). Rotation re-checks it against the user's
   * current version under the per-user lock, so a credential whose proof was
   * invalidated by a later factor change can never mint a live session.
   */
  @Column(name = "security_version", nullable = false)
  private int securityVersion;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  public RefreshToken(UUID userId, String tokenHash, Instant expiresAt) {
    this.userId = userId;
    this.tokenHash = tokenHash;
    this.expiresAt = expiresAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getTokenHash() { return tokenHash; }
  public Instant getExpiresAt() { return expiresAt; }
  public boolean isRevoked() { return revoked; }
  public void setRevoked(boolean v) { revoked = v; }
  public int getSecurityVersion() { return securityVersion; }
  public void setSecurityVersion(int v) { securityVersion = v; }
  public Instant getCreatedAt() { return createdAt; }
}
