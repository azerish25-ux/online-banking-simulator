package com.bank.platform.ledger;

public enum TxKind {
  TRANSFER,
  DEPOSIT,
  INTEREST,
  /** An authorized reversal of a posted TRANSFER or DEPOSIT (V29). */
  REVERSAL
}