package com.bank.platform.ledger;

/**
 * POSTED rows moved money and are final. HELD rows are transfers at or above
 * the review threshold awaiting an operator decision - no money has moved.
 * CANCELLED rows were declined by operations; nothing was ever debited or
 * credited, so there is nothing to reverse.
 */
public enum TxStatus {
  POSTED,
  HELD,
  CANCELLED
}
