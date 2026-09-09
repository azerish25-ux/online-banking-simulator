package com.bank.platform.notifications;

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
 * One committed external-delivery intent. The row is written in the
 * SAME transaction as the operation that produced it, and only a worker that
 * claims it AFTER that commit may call the provider: so a rolled-back
 * operation never sends mail, and a committed operation's mail cannot be
 * lost to a crash before delivery (it stays PENDING and is picked up again).
 * Delivery is at-least-once: if the provider fails after accepting, the row
 * retries (attempts + backoff) and may duplicate: the honest contract for a
 * stub with no provider-side idempotency. Rows that exhaust their budget
 * dead-letter to {@link Status#FAILED} for an operator.
 */
@Entity
@Table(name = "email_outbox")
public class EmailOutbox {

  public enum Status {
    /** Committed with the operation, not yet claimed by the worker. */
    PENDING,
    /** Claimed by a worker; the provider call is in flight. */
    DELIVERING,
    /** Provider accepted the mail. */
    SENT,
    /** Exhausted retries: operator review required. */
    FAILED
  }

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(nullable = false, length = 320)
  private String email;

  @Column(name = "notification_id", updatable = false)
  private UUID notificationId;

  @Column(name = "delivery_key", nullable = false, updatable = false, length = 255)
  private String deliveryKey;

  @Column(nullable = false, length = 120)
  private String subject;

  @Column(nullable = false, length = 500)
  private String body;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private Status status = Status.PENDING;

  @Column(nullable = false)
  private int attempts;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 255)
  private String lastError;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EmailOutbox() {}

  public EmailOutbox(UUID userId, String email, UUID notificationId, String deliveryKey,
      String subject, String body) {
    this.userId = userId;
    this.email = email;
    this.notificationId = notificationId;
    this.deliveryKey = deliveryKey;
    this.subject = subject;
    this.body = body;
    Instant now = Instant.now();
    this.nextAttemptAt = now;
    this.updatedAt = now;
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
  public String getEmail() { return email; }
  public UUID getNotificationId() { return notificationId; }
  public String getDeliveryKey() { return deliveryKey; }
  public String getSubject() { return subject; }
  public String getBody() { return body; }
  public Status getStatus() { return status; }
  public void setStatus(Status status) { this.status = status; }
  public int getAttempts() { return attempts; }
  public void setAttempts(int attempts) { this.attempts = attempts; }
  public Instant getNextAttemptAt() { return nextAttemptAt; }
  public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void touch(Instant now) { this.updatedAt = now; }
}
