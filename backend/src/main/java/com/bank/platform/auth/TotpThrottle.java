package com.bank.platform.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Per-account throttle for TOTP code verification (the MFA login challenge,
 * and enabling/disabling 2FA from a session). The per-IP auth rate limit
 * stops credential stuffing from a single address, but it cannot stop code
 * guessing from many addresses or from a session an attacker already holds -
 * and six digits are only 10^6 values. A small per-account failure budget per
 * window makes brute force impractical without locking out a user for long.
 *
 * Buckets live in a bounded, time-evicted cache (like the auth rate limiter):
 * an idle account stops costing memory and the table cannot grow forever.
 * Success resets the counter so a user who fumbles a code twice then enters
 * the right one is not punished in the next window.
 */
@Component
public class TotpThrottle {

  /** Wrong codes allowed per window; the next verification attempt is refused. */
  static final int MAX_ATTEMPTS = 5;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Cache<String, Attempts> attempts = Caffeine.newBuilder()
      .expireAfterWrite(WINDOW)
      .maximumSize(10_000)
      .build();

  /** Throws unless the account still has verification attempts left this window. */
  public void verifyAvailable(String email) {
    if (counter(email).failures() >= MAX_ATTEMPTS) {
      throw new TooManyTotpAttemptsException();
    }
  }

  /** Records one wrong code for the account (idempotent under parallel calls). */
  public void recordFailure(String email) {
    counter(email).fail();
  }

  /** Clears the budget after a correct code, so a fumbled-then-correct login is not punished. */
  public void recordSuccess(String email) {
    attempts.invalidate(normalize(email));
  }

  private Attempts counter(String email) {
    return attempts.get(normalize(email), ignored -> new Attempts());
  }

  private static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase();
  }

  private static final class Attempts {
    private int failures;

    synchronized void fail() {
      failures++;
    }

    synchronized int failures() {
      return failures;
    }
  }
}
