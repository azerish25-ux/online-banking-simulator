package com.bank.platform.auth;

/** An account burned its authentication failure budget (429 with Retry-After). */
public class AuthThrottledException extends RuntimeException {
  public AuthThrottledException() {
    super("Too many failed attempts. Try again later.");
  }
}
