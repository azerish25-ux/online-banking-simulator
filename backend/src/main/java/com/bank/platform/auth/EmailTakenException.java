package com.bank.platform.auth;

public class EmailTakenException extends RuntimeException {
  public EmailTakenException(String email) {
    super("An account with email " + email + " already exists");
  }
}
