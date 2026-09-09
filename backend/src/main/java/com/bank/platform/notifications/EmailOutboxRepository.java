package com.bank.platform.notifications;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, UUID> {

  Optional<EmailOutbox> findByDeliveryKey(String deliveryKey);

  Page<EmailOutbox> findByStatusOrderByCreatedAtDesc(EmailOutbox.Status status, Pageable pageable);

  /**
   * PENDING rows that are due. A never-attempted row (attempts = 0) is ALWAYS
   * due: its next_attempt_at was set to enqueue-time and database timestamp
   * rounding can push that stored value a hair past the poll instant, which
   * would otherwise make a freshly committed row wait for the next poll.
   * Once a row has been attempted, next_attempt_at (set with backoff) is the
   * sole gate.
   */
  @Query("select o.id from EmailOutbox o where o.status = 'PENDING' "
      + "and (o.nextAttemptAt <= :dueCutoff or o.attempts = 0) "
      + "order by o.nextAttemptAt asc")
  List<UUID> findPendingIds(Instant dueCutoff, Pageable pageable);

  /** A worker crashed mid-delivery: reclaim rows stuck DELIVERING past the stale age. */
  @Query("select o.id from EmailOutbox o where o.status = 'DELIVERING' "
      + "and o.updatedAt < :staleBefore order by o.updatedAt asc")
  List<UUID> findStaleDeliveringIds(Instant staleBefore, Pageable pageable);

  /**
   * Atomic claim: the conditional status flip is the concurrency guard: two
   * workers can both read the same candidate id, but exactly one UPDATE wins.
   * Returns 1 for the winner, 0 for everyone else. Fresh rows (attempts = 0)
   * are always claimable (see {@link #findPendingIds}); retries wait for
   * their backoff. Each @Modifying flip runs in its own transaction (the
   * worker deliberately holds none across the external provider call).
   */
  @Transactional
  @Modifying
  @Query("update EmailOutbox o set o.status = 'DELIVERING', o.updatedAt = :now "
      + "where o.id = :id and o.status = 'PENDING' "
      + "and (o.nextAttemptAt <= :dueCutoff or o.attempts = 0)")
  int claim(@Param("id") UUID id, @Param("now") Instant now, @Param("dueCutoff") Instant dueCutoff);

  @Transactional
  @Modifying
  @Query("update EmailOutbox o set o.status = 'DELIVERING', o.updatedAt = :now "
      + "where o.id = :id and o.status = 'DELIVERING' and o.updatedAt < :staleBefore")
  int reclaimStale(@Param("id") UUID id, @Param("now") Instant now, @Param("staleBefore") Instant staleBefore);

  /** Provider accepted the mail. */
  @Transactional
  @Modifying
  @Query("update EmailOutbox o set o.status = 'SENT', o.updatedAt = :now, o.lastError = null "
      + "where o.id = :id and o.status = 'DELIVERING'")
  int markSent(@Param("id") UUID id, @Param("now") Instant now);

  /** Delivery failed: retry with backoff until the budget is spent, then dead-letter. */
  @Transactional
  @Modifying
  @Query("update EmailOutbox o set o.status = :to, o.attempts = :attempts, "
      + "o.nextAttemptAt = :nextAttemptAt, o.lastError = :error, o.updatedAt = :now "
      + "where o.id = :id and o.status = 'DELIVERING'")
  int recordFailure(@Param("id") UUID id, @Param("to") EmailOutbox.Status to,
      @Param("attempts") int attempts, @Param("nextAttemptAt") Instant nextAttemptAt,
      @Param("error") String error, @Param("now") Instant now);

  /** Operator requeues a dead letter: back to PENDING with a fresh budget. */
  @Transactional
  @Modifying
  @Query("update EmailOutbox o set o.status = 'PENDING', o.attempts = 0, o.lastError = null, "
      + "o.nextAttemptAt = :now, o.updatedAt = :now where o.id = :id and o.status = 'FAILED'")
  int requeue(@Param("id") UUID id, @Param("now") Instant now);

  /** SENT rows older than the retention window are housekeeping, not history. */
  @Transactional
  @Modifying
  @Query("delete from EmailOutbox o where o.status = 'SENT' and o.updatedAt < :cutoff")
  int deleteSentBefore(@Param("cutoff") Instant cutoff);
}
