package com.bank.platform.cards;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cards")
public class Card {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "account_id", nullable = false)
  private UUID accountId;

  @Column(nullable = false, length = 4)
  private String last4;

  @Column(name = "pan_hash", nullable = false, length = 64)
  private String panHash;

  @Column(name = "cvv_hash", nullable = false, length = 64)
  private String cvvHash;

  @Column(name = "exp_month", nullable = false)
  private int expMonth;

  @Column(name = "exp_year", nullable = false)
  private int expYear;

  @Column(nullable = false, length = 32)
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Card() {}

  public Card(UUID userId, UUID accountId, String last4, String panHash, String cvvHash, int expMonth, int expYear) {
    this.userId = userId;
    this.accountId = accountId;
    this.last4 = last4;
    this.panHash = panHash;
    this.cvvHash = cvvHash;
    this.expMonth = expMonth;
    this.expYear = expYear;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public UUID getAccountId() { return accountId; }
  public String getLast4() { return last4; }
  public int getExpMonth() { return expMonth; }
  public int getExpYear() { return expYear; }
  public String getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public void setStatus(String v) { status = v; }
}
