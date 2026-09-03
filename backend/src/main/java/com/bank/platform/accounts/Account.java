package com.bank.platform.accounts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
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
  private String type = "CHECKING";

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal balance = BigDecimal.ZERO;

  @Column(nullable = false, length = 32)
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Account() {}

  public Account(UUID userId, String iban, String type) {
    this.userId = userId;
    this.iban = iban;
    this.type = type;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getIban() { return iban; }
  public String getType() { return type; }
  public BigDecimal getBalance() { return balance; }
  public String getStatus() { return status; }
  public void setBalance(BigDecimal v) { balance = v; }
  public void setStatus(String v) { status = v; }
}
