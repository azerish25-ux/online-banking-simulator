package com.bank.platform.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One password-stage MFA login challenge. A challenge carries its own
 * random identity, the user it was minted for, an expiry, a consumption state
 * and an attempt budget. Verification must atomically transition exactly one
 * unused, unexpired challenge to consumed; replays, concurrent twins and
 * expired rows can never mint a session.
 */
@Entity
@Table(name = "login_challenges")
public class LoginChallenge {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(nullable = false)
  private boolean consumed;

  @Column(name = "failed_attempts", nullable = false)
  private int failedAttempts;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected LoginChallenge() {}

  public LoginChallenge(UUID userId, Instant expiresAt) {
    this.userId = userId;
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
  public Instant getExpiresAt() { return expiresAt; }
  public boolean isConsumed() { return consumed; }
  public int getFailedAttempts() { return failedAttempts; }
  public Instant getCreatedAt() { return createdAt; }
}
