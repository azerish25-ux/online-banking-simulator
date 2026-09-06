package com.bank.platform.auth;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginChallengeRepository extends JpaRepository<LoginChallenge, UUID> {

  /**
   * Atomically consumes one challenge. Returns 1 only when this caller won the
   * race on an unused, unexpired challenge; 0 when it was already consumed,
   * never existed as usable, or expired - in which case the caller rejects the
   * attempt and no second session is minted. Two simultaneous correct
   * submissions can therefore never both succeed.
   */
  @Modifying
  @Query("update LoginChallenge c set c.consumed = true "
      + "where c.id = :id and c.consumed = false and c.expiresAt > :now")
  int consume(@Param("id") UUID id, @Param("now") Instant now);

  /**
   * Durable per-challenge attempt accounting. Runs in its own transaction
   * (see LoginChallengeService.recordFailure) so the rollback that follows a
   * failed verification can never erase the attempt.
   */
  @Modifying
  @Query("update LoginChallenge c set c.failedAttempts = c.failedAttempts + 1 "
      + "where c.id = :id and c.consumed = false")
  int recordFailure(@Param("id") UUID id);

  /** Bounded retention: expired rows (consumed or abandoned) leave the table. */
  @Modifying
  @Query("delete from LoginChallenge c where c.expiresAt < :now")
  int purgeExpired(@Param("now") Instant now);
}
