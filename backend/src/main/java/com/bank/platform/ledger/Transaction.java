package com.bank.platform.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

  @Column(name = "idempotency_key", unique = true, length = 64)
  private String idempotencyKey;

  @Column(nullable = false, length = 32)
  private String status = "POSTED";

  @Column(length = 140)
  private String memo;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

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
  public String getStatus() { return status; }
  public String getMemo() { return memo; }
  public Instant getCreatedAt() { return createdAt; }

  public void setFromAccountId(UUID v) { fromAccountId = v; }
  public void setToAccountId(UUID v) { toAccountId = v; }
  public void setAmount(BigDecimal v) { amount = v; }
  public void setCurrency(String v) { currency = v; }
  public void setIdempotencyKey(String v) { idempotencyKey = v; }
  public void setMemo(String v) { memo = v; }
}
