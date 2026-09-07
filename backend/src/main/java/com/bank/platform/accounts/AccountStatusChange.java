package com.bank.platform.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable account status transition (V27): FROZEN or ACTIVE, with the
 * moment it took effect. Written in the same transaction as the status change
 * itself. The interest job reads these to price only days the account was
 * ACTIVE, so a frozen period never accrues and is never retroactively charged
 * after re-activation. Append-only.
 */
@Entity
@Table(name = "account_status_history")
public class AccountStatusChange {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(nullable = false, length = 16, updatable = false)
  @Enumerated(EnumType.STRING)
  private AccountStatus status;

  @Column(name = "changed_at", nullable = false, updatable = false)
  private Instant changedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected AccountStatusChange() {}

  public AccountStatusChange(UUID accountId, AccountStatus status, Instant changedAt) {
    this.accountId = accountId;
    this.status = status;
    this.changedAt = changedAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getAccountId() { return accountId; }
  public AccountStatus getStatus() { return status; }
  public Instant getChangedAt() { return changedAt; }
}
