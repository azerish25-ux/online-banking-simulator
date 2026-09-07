package com.bank.platform.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One account × accrual period row (F16). The database uniqueness on
 * (account_id, period) is what makes interest deterministic and resumable: a
 * scheduler run and an operator trigger can overlap all they like - the
 * second one to post an account's period hits the unique constraint, rolls
 * that account's unit back, and moves on. A failed batch is resumed by the
 * next run, which only takes the accounts without a row yet.
 */
@Entity
@Table(name = "interest_accruals")
public class InterestAccrual {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "account_id", nullable = false, updatable = false)
  private UUID accountId;

  /** The accrual month (YYYY-MM) whose balances this row prices. */
  @Column(nullable = false, length = 7, updatable = false)
  private String period;

  /** Rate version in effect when the accrual was computed (future-proofed). */
  @Column(name = "rate_version", nullable = false, updatable = false)
  private int rateVersion;

  @Column(nullable = false, length = 16, updatable = false)
  private String status = "POSTED";

  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  /**
   * The priced basis: average daily closing balance (savings) or the
   * outstanding principal charged (loans), for auditability of the amount.
   */
  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal basis;

  /** Eligible days that contributed to the basis (savings and loans alike). */
  @Column(name = "day_count", nullable = false, updatable = false)
  private int dayCount;

  @Column(name = "posted_at", nullable = false, updatable = false)
  private Instant postedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected InterestAccrual() {}

  public InterestAccrual(UUID accountId, String period, int rateVersion,
      BigDecimal amount, BigDecimal basis, int dayCount, Instant postedAt) {
    this.accountId = accountId;
    this.period = period;
    this.rateVersion = rateVersion;
    this.amount = amount;
    this.basis = basis;
    this.dayCount = dayCount;
    this.postedAt = postedAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public UUID getAccountId() { return accountId; }
  public String getPeriod() { return period; }
  public int getRateVersion() { return rateVersion; }
  public String getStatus() { return status; }
  public BigDecimal getAmount() { return amount; }
  public BigDecimal getBasis() { return basis; }
  public int getDayCount() { return dayCount; }
  public Instant getPostedAt() { return postedAt; }
}
