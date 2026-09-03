package com.bank.platform.beneficiaries;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiaries")
public class Beneficiary {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(nullable = false, length = 80)
  private String nickname;

  @Column(nullable = false, length = 34)
  private String iban;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Beneficiary() {}

  public Beneficiary(UUID userId, String nickname, String iban) {
    this.userId = userId;
    this.nickname = nickname;
    this.iban = iban;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getNickname() { return nickname; }
  public String getIban() { return iban; }
  public Instant getCreatedAt() { return createdAt; }
}
