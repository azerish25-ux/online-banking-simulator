package com.bank.platform.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, unique = true, length = 34)
  private String iban;

  @Column(nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private AccountType type = AccountType.CHECKING;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal balance = BigDecimal.ZERO;

  @Column(nullable = false, length = 32)
  @Enumerated(EnumType.STRING)
  private AccountStatus status = AccountStatus.ACTIVE;

  @Column(name = "credit_limit", nullable = false, precision = 19, scale = 4)
  private BigDecimal creditLimit = BigDecimal.ZERO;

  @Column(name = "last_interest_at")
  private Instant lastInterestAt;

  /**
   * Optimistic lock (see V16): a write whose read snapshot is stale throws
   * instead of silently overwriting a newer balance. Money movements lock
   * the row first, so they never hit this; it exists to make any future
   * unlocked read-modify-write fail loudly instead of corrupting the ledger.
   */
  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "updated_at")
  private Instant updatedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Account() {}

  public Account(UUID userId, String iban, AccountType type) {
    this.userId = userId;
    this.iban = iban;
    this.type = type;
  }

  @PreUpdate
  void touch() { updatedAt = Instant.now(); }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getIban() { return iban; }
  public AccountType getType() { return type; }
  public BigDecimal getBalance() { return balance; }
  public AccountStatus getStatus() { return status; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setBalance(BigDecimal v) { balance = v; }
  public void setStatus(AccountStatus v) { status = v; }
  public BigDecimal getCreditLimit() { return creditLimit; }
  public void setCreditLimit(BigDecimal v) { creditLimit = v; }
  public Instant getLastInterestAt() { return lastInterestAt; }
  public void setLastInterestAt(Instant v) { lastInterestAt = v; }
}
