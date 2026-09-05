package com.bank.platform.accounts;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {
  public AccountNotFoundException(UUID id) { super("Account " + id + " not found"); }
  public AccountNotFoundException(String iban) { super("Account " + iban + " not found"); }

  /**
   * A resolution-context message (transfers): same 404, but the detail says
   * WHY the lookup failed instead of echoing the searched value. The generic
   * constructors stay for paths where echoing the subject is the right detail.
   */
  public AccountNotFoundException(String subject, String detail) {
    super(detail);
  }
}
