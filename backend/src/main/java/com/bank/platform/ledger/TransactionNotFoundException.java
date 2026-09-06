package com.bank.platform.ledger;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
  public TransactionNotFoundException(UUID id) {
    super("Transaction " + id + " not found");
  }

  public TransactionNotFoundException(String message) {
    super(message);
  }
}
