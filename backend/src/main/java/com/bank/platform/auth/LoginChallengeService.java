package com.bank.platform.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle owner for persisted login challenges (F30). Issuing creates one
 * unused row with a bounded lifetime; verifying consumes it atomically in the
 * caller's transaction; failed attempts are recorded in a SEPARATE transaction
 * so the rollback that follows a rejected code can never erase the attempt
 * budget (a naive same-transaction counter would reset on every exception and
 * hand an attacker unlimited guesses).
 */
@Service
public class LoginChallengeService {

  /** Wrong codes allowed per challenge; the next verification is refused. */
  static final int MAX_ATTEMPTS = 5;
  private static final Duration LIFETIME = Duration.ofMinutes(5);

  private final LoginChallengeRepository challenges;
  private final Clock clock;

  public LoginChallengeService(LoginChallengeRepository challenges, Clock clock) {
    this.challenges = challenges;
    this.clock = clock;
  }

  /** Creates one fresh, unused challenge for the just-password-verified user. */
  @Transactional
  public LoginChallenge issue(User user) {
    // Opportunistic bounded retention on every mint (like refresh tokens).
    challenges.purgeExpired(clock.instant());
    return challenges.save(new LoginChallenge(user.getId(), clock.instant().plus(LIFETIME)));
  }

  /**
   * Durable attempt accounting that survives the surrounding rollback.
   * REQUIRES_NEW suspends the caller's (about-to-roll-back) transaction and
   * commits the increment on its own.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(UUID challengeId) {
    challenges.recordFailure(challengeId);
  }

  @Transactional
  public void purgeExpired() {
    challenges.purgeExpired(clock.instant());
  }

  /** Bounded retention even when no login happens (abandoned challenges). */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
  public void purgeExpiredScheduled() {
    purgeExpired();
  }
}
