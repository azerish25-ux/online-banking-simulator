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
 * One signed posting of a journal entry (F15). A line names exactly one side:
 * a customer {@code accountId} (whose balance projection this line moves -
 * positive increases the balance) or a named {@code counteraccount} (the
 * balancing side that keeps signed deltas summing to zero per currency).
 * Immutable by construction; {@code postedAt} mirrors the entry's posting
 * time so per-account windows (e.g. daily closing balances for interest) can
 * be derived straight from the lines without joining back to entries.
 */
@Entity
@Table(name = "journal_lines")
public class JournalLine {

  public static final String SIMULATOR_FUNDING = "SIMULATOR_FUNDING";
  public static final String INTEREST = "INTEREST";
  public static final String MIGRATION_OPENING = "MIGRATION_OPENING";

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "entry_id", nullable = false, updatable = false)
  private UUID entryId;

  @Column(name = "account_id", updatable = false)
  private UUID accountId;

  @Column(updatable = false, length = 32)
  private String counteraccount;

  /** Signed delta in currency units: customer + increases the balance. */
  @Column(nullable = false, updatable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  /** Deterministic position inside its entry (0-based, written in order). */
  @Column(nullable = false, updatable = false)
  private int ordinal;

  @Column(name = "posted_at", nullable = false, updatable = false)
  private Instant postedAt;

  protected JournalLine() {}

  public JournalLine(UUID entryId, UUID accountId, String counteraccount,
      BigDecimal amount, int ordinal, Instant postedAt) {
    this.entryId = entryId;
    this.accountId = accountId;
    this.counteraccount = counteraccount;
    this.amount = amount;
    this.ordinal = ordinal;
    this.postedAt = postedAt;
  }

  @PrePersist
  void prePersist() {
    if (id == null) id = UUID.randomUUID();
  }

  public UUID getId() { return id; }
  public UUID getEntryId() { return entryId; }
  public UUID getAccountId() { return accountId; }
  public String getCounteraccount() { return counteraccount; }
  public BigDecimal getAmount() { return amount; }
  public int getOrdinal() { return ordinal; }
  public Instant getPostedAt() { return postedAt; }

  /** A posting against a customer account (projection). */
  public static JournalLine account(UUID entryId, UUID accountId, BigDecimal amount,
      int ordinal, Instant postedAt) {
    return new JournalLine(entryId, accountId, null, amount, ordinal, postedAt);
  }

  /** A posting against a named counteraccount (funding/interest/migration). */
  public static JournalLine counter(UUID entryId, String counteraccount, BigDecimal amount,
      int ordinal, Instant postedAt) {
    return new JournalLine(entryId, null, counteraccount, amount, ordinal, postedAt);
  }
}
