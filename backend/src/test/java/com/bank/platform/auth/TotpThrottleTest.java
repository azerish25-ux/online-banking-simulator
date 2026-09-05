package com.bank.platform.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TotpThrottleTest {

  private final TotpThrottle throttle = new TotpThrottle();

  @Test
  void allowsFailuresUpToTheBudgetThenRefuses() {
    String email = "victim@example.com";
    for (int i = 0; i < TotpThrottle.MAX_ATTEMPTS; i++) {
      throttle.recordFailure(email);
    }
    assertThrows(TooManyTotpAttemptsException.class, () -> throttle.verifyAvailable(email));
  }

  @Test
  void keysBucketsPerAccountCaseInsensitively() {
    // Burn the budget for one account; a different account is unaffected.
    for (int i = 0; i < TotpThrottle.MAX_ATTEMPTS; i++) {
      throttle.recordFailure("victim@example.com");
    }
    assertThrows(TooManyTotpAttemptsException.class, () -> throttle.verifyAvailable("victim@example.com"));
    assertThrows(TooManyTotpAttemptsException.class, () -> throttle.verifyAvailable("VICTIM@Example.COM "));
    assertDoesNotThrow(() -> throttle.verifyAvailable("other@example.com"));
  }

  @Test
  void successResetsTheBudget() {
    String email = "fumbler@example.com";
    // Fumble twice, then succeed - the success must clear the count so the
    // next window does not inherit the earlier mistakes.
    throttle.recordFailure(email);
    throttle.recordFailure(email);
    throttle.recordSuccess(email);
    assertDoesNotThrow(() -> throttle.verifyAvailable(email));
    for (int i = 0; i < TotpThrottle.MAX_ATTEMPTS; i++) {
      throttle.recordFailure(email);
    }
    assertThrows(TooManyTotpAttemptsException.class, () -> throttle.verifyAvailable(email));
  }
}
