package com.bank.platform.ledger;

public class TransferValidationException extends RuntimeException {
  public TransferValidationException(String message) { super(message); }
}
