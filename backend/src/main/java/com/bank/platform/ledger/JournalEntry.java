package com.bank.platform.ledger;

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
 * One balanced group of posted accounting entries (F15). Immutable by
 * construction - the DB forbids UPDATE/DELETE on PostgreSQL (V21) and this
 * class exposes no setters - so a correction must be a NEW authorized entry
 * linked through {@link #reversesEntryId}, never an edit of history.
 */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(nullable = false, length = 24)
  @Enumerated(EnumType.STRING)
  private JournalKind kind;

  /** The originating business row (e.g. the transactions.id the entry books). */
  @Column(name = "operation_ref", nullable = false, length = 80)
  private String operationRef;

  @Column(nullable = false, length = 3)
  private String currency = "USD";

  /** When money moved - the journal's authoritative posting timestamp. */
  @Column(name = "posted_at", nullable = false)
  private Instant postedAt;

  @Column(length = 255)
  private String memo;

  /** A correction links back to the original entry it reverses. */
  @Column(name = "reverses_entry_id")
  private UUID reversesEntryId;

  /** Monotonic insert sequence (DB identity) - the deterministic ordering key. */
  @Column(insertable = false, updatable = false)
  private Long seq;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected JournalEntry() {}

  public JournalEntry(JournalKind kind, String operationRef, String currency,
      Instant postedAt, String memo) {
    this.kind = kind;
    this.operationRef = operationRef;
    this.currency = currency;
    this.postedAt = postedAt;
    this.memo = memo;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
    if (createdAt == null) createdAt = Instant.now();
  }

  public UUID getId() { return id; }
  public JournalKind getKind() { return kind; }
  public String getOperationRef() { return operationRef; }
  public String getCurrency() { return currency; }
  public Instant getPostedAt() { return postedAt; }
  public String getMemo() { return memo; }
  public UUID getReversesEntryId() { return reversesEntryId; }
  public void setReversesEntryId(UUID v) { reversesEntryId = v; }
  public Long getSeq() { return seq; }
  public Instant getCreatedAt() { return createdAt; }
}
