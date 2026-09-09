package com.bank.platform.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One immutable principal-component movement (V26): a draw, a principal
 * repayment or the legacy cutover baseline. Signed {@code amount} follows the
 * journal convention: positive increases the outstanding principal, negative
 * reduces it. Written in the SAME transaction as the money movement that
 * produced it and keyed to that transaction (unique per account + source), so
 * principal history can never drift from the general journal or be recorded
 * twice. Append-only, like the journal: accrual pricing reads these rows,
 * nothing ever edits them.
 */
@Entity
@Table(name = "principal_movements")
public class PrincipalMovement {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  @Column(nullable = false, length = 24, updatable = false)
  @Enumerated(EnumType.STRING)
  private PrincipalKind kind;

  /** Signed delta: + principal up (draw/cutover), - principal down (repayment). */
  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(name = "source_transaction_id", updatable = false)
  private UUID sourceTransactionId;

  @Column(name = "posted_at", nullable = false, updatable = false)
  private Instant postedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PrincipalMovement() {}

  public PrincipalMovement(UUID accountId, PrincipalKind kind, BigDecimal signedAmount,
      UUID sourceTransactionId, Instant postedAt) {
    this.accountId = accountId;
    this.kind = kind;
    this.amount = signedAmount;
    this.sourceTransactionId = sourceTransactionId;
    this.postedAt = postedAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getAccountId() { return accountId; }
  public PrincipalKind getKind() { return kind; }
  public BigDecimal getAmount() { return amount; }
  public UUID getSourceTransactionId() { return sourceTransactionId; }
  public Instant getPostedAt() { return postedAt; }
}
