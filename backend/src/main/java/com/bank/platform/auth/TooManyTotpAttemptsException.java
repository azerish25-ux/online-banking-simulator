package com.bank.platform.auth;

/**
 * Signalled when an account burns its TOTP verification budget for the current
 * window. Mapped to 429 with a Retry-After header by the global handler.
 */
public class TooManyTotpAttemptsException extends RuntimeException {

  public TooManyTotpAttemptsException() {
    super("Too many invalid codes: try again in a minute");
  }
}
