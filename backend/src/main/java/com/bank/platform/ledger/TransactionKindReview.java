package com.bank.platform.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One archived classification question (F21). When V24 (or any future
 * evidence pass) corrects or cannot prove a legacy transaction's kind, the
 * original classification, the reason, and the memo excerpt are preserved
 * here for operator review - balances and history are never altered to make
 * reports fit, and this table is the visible record of what was decided.
 */
@Entity
@Table(name = "transaction_kind_review")
public class TransactionKindReview {

  @Id
  @Column(name = "transaction_id", updatable = false)
  private UUID transactionId;

  @Column(name = "prior_kind", nullable = false, length = 16)
  private String priorKind;

  @Column(nullable = false, length = 255)
  private String reason;

  @Column(name = "memo_snippet", length = 140)
  private String memoSnippet;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected TransactionKindReview() {}

  public TransactionKindReview(UUID transactionId, String priorKind, String reason,
      String memoSnippet) {
    this.transactionId = transactionId;
    this.priorKind = priorKind;
    this.reason = reason;
    this.memoSnippet = memoSnippet;
    this.createdAt = Instant.now();
  }

  public UUID getTransactionId() { return transactionId; }
  public String getPriorKind() { return priorKind; }
  public String getReason() { return reason; }
  public String getMemoSnippet() { return memoSnippet; }
  public Instant getCreatedAt() { return createdAt; }
}
