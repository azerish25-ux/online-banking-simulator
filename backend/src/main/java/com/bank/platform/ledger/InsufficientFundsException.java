package com.bank.platform.ledger;

public class InsufficientFundsException extends RuntimeException {
  public InsufficientFundsException() { super("Insufficient funds for this transfer"); }
}
