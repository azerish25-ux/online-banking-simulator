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
  OPENING_BALANCE
}
