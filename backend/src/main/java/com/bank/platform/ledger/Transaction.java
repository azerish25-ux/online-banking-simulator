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

@Entity
@Table(name = "transactions")
public class Transaction {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "from_account_id")
  private UUID fromAccountId;

  @Column(name = "to_account_id")
  private UUID toAccountId;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(nullable = false, length = 3)
  private String currency = "USD";

  // Uniqueness is enforced in the DB as a composite index on
  // (from_account_id, idempotency_key) - see V11. A global unique here would
  // let two unrelated users collide on the same key string.
  @Column(name = "idempotency_key", length = 64)
  private String idempotencyKey;

  /**
   * Canonical SHA-256 of the operation intent: source, destination,
   * exact normalized amount, currency and normalized memo. An idempotent
   * replay carries the same hash and returns the original row; a reuse of the
   * key for a *different* intent hashes differently and is a conflict, never
   * a silent replay of older money.
   */
  @Column(name = "request_hash", length = 64)
  private String requestHash;

  @Column(nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private TxStatus status = TxStatus.POSTED;

  @Column(nullable = false, length = 16)
  @Enumerated(EnumType.STRING)
  private TxKind kind = TxKind.TRANSFER;

  @Column(nullable = false)
  private boolean flagged;

  @Column(nullable = false)
  private boolean reviewed;

  @Column(length = 140)
  private String memo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /**
   * When money actually moved. Only POSTED rows carry one: HELD and
   * CANCELLED rows are intents and keep it null. Reporting (statements,
   * monthly summaries, daily totals, public stats) cuts and buckets on this,
   * never on {@link #createdAt}, so an approval that lands after a month
   * boundary posts into the month it settled in.
   */
  @Column(name = "posted_at")
  private Instant postedAt;

  /**
   * For a REVERSAL row (V29): the id of the POSTED transaction it reverses.
   * The original row is untouched - its history stays as it was; the reversal
   * is a NEW linked operation that moves the money back. At most one reversal
   * per original (PostgreSQL partial unique index; checked in the service on
   * H2).
   */
  @Column(name = "reverses_transaction_id")
  private UUID reversesTransactionId;

  /** The operator's mandatory reason, preserved on the reversal row itself. */
  @Column(name = "reversal_reason", length = 255)
  private String reversalReason;

  // Monotonic insert sequence (DB identity, see V14). Listings tie-break equal
  // created_at values on this column so "newest first" is total: the random
  // UUID id cannot express insertion order. Read-only - the database assigns it.
  @Column(insertable = false, updatable = false)
  private Long seq;

  protected Transaction() {}

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getFromAccountId() { return fromAccountId; }
  public UUID getToAccountId() { return toAccountId; }
  public BigDecimal getAmount() { return amount; }
  public String getCurrency() { return currency; }
  public String getIdempotencyKey() { return idempotencyKey; }
  public String getRequestHash() { return requestHash; }
  public void setRequestHash(String v) { requestHash = v; }
  public TxStatus getStatus() { return status; }
  public void setStatus(TxStatus v) { status = v; }
  public TxKind getKind() { return kind; }
  public void setKind(TxKind v) { kind = v; }
  public String getMemo() { return memo; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant v) { createdAt = v; }
  public Instant getPostedAt() { return postedAt; }
  public void setPostedAt(Instant v) { postedAt = v; }
  public UUID getReversesTransactionId() { return reversesTransactionId; }
  public void setReversesTransactionId(UUID v) { reversesTransactionId = v; }
  public String getReversalReason() { return reversalReason; }
  public void setReversalReason(String v) { reversalReason = v; }
  public Long getSeq() { return seq; }

  public void setFromAccountId(UUID v) { fromAccountId = v; }
  public void setToAccountId(UUID v) { toAccountId = v; }
  public void setAmount(BigDecimal v) { amount = v; }
  public void setCurrency(String v) { currency = v; }
  public void setIdempotencyKey(String v) { idempotencyKey = v; }
  public void setMemo(String v) { memo = v; }
  public boolean isFlagged() { return flagged; }
  public void setFlagged(boolean v) { flagged = v; }
  public boolean isReviewed() { return reviewed; }
  public void setReviewed(boolean v) { reviewed = v; }
}
