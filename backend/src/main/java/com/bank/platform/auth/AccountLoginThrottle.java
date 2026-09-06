package com.bank.platform.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Per-ACCOUNT authentication throttle (F03). The per-IP filter cannot stop
 * credential stuffing spread across many addresses, so failed logins for one
 * normalized account also share a bounded budget. Keys are normalized emails
 * and NEVER disclose whether the account exists: an unknown email burns the
 * same budget an existing one would, so probing for accounts is throttled
 * just like attacking them. Success resets the counter so a fumbled password
 * is not punished forever.
 */
@Component
public class AccountLoginThrottle {

  /** Failed logins allowed per window; the next attempt is refused. */
  static final int MAX_FAILURES = 10;
  private static final Duration WINDOW = Duration.ofMinutes(15);

  private final Cache<String, Failures> attempts = Caffeine.newBuilder()
      .expireAfterWrite(WINDOW)
      .maximumSize(50_000)
      .build();

  /** Throws unless the account still has login attempts left this window. */
  public void verifyAvailable(String email) {
    if (counter(email).count() >= MAX_FAILURES) {
      throw new AuthThrottledException();
    }
  }

  /** Records one failed login (idempotent under parallel calls). */
  public void recordFailure(String email) {
    counter(email).fail();
  }

  /** Clears the budget after a successful login. */
  public void recordSuccess(String email) {
    attempts.invalidate(normalize(email));
  }

  private Failures counter(String email) {
    return attempts.get(normalize(email), ignored -> new Failures());
  }

  private static String normalize(String email) {
    return email == null ? "" : email.trim().toLowerCase();
  }

  private static final class Failures {
    private int count;

    synchronized void fail() {
      count++;
    }

    synchronized int count() {
      return count;
    }
  }
}
