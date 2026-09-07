package com.bank.platform.ledger;

/** The kind of business operation a journal entry records (F15). */
public enum JournalKind {
  /** A funding deposit; the balancing side is SIMULATOR_FUNDING. */
  DEPOSIT,
  /** A customer-to-customer (or loan draw/repayment) transfer. */
  TRANSFER,
  /** A monthly interest posting; the balancing side is INTEREST. */
  INTEREST,
  /** A labelled cutover entry that reproduces a pre-journal balance. */
  OPENING_BALANCE,
  /**
   * A linked correcting entry (V29): reverses an original posting via
   * {@code reverses_entry_id}. Never an edit of the original entry.
   */
  REVERSAL
}
