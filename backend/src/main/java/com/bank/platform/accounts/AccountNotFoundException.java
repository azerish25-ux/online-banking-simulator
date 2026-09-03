package com.bank.platform.accounts;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
  public AccountNotFoundException(UUID id) { super("Account " + id + " not found"); }
  public AccountNotFoundException(String iban) { super("Account " + iban + " not found"); }
}
