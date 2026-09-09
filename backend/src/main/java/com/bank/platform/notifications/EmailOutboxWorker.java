package com.bank.platform.notifications;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Delivery half. Runs on a schedule and ONLY ever touches rows a
 * previous transaction COMMITTED (PENDING rows were written in the
 * operation's transaction; DELIVERING rows that went stale are reclaimed).
 * A claim is an atomic conditional status flip, so concurrent workers (or
 * overlapping runs) each deliver a row exactly once per claim.
 *
 * <p>Delivery is at-least-once: the provider is called AFTER the claim
 * commits, and a provider that fails after accepting (delivery-before-ack
 * failure) leaves the row to retry: with backoff up to a bounded budget: * because the stub cannot promise provider-side idempotency. Rows that
 * exhaust the budget dead-letter to FAILED for an operator. Simulated
 * delivery stays clearly labelled by the {@link NotificationService.EmailSender}.
 */
@Service
public class EmailOutboxWorker {

  private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);

  private final EmailOutboxRepository outbox;
  private final NotificationService.EmailSender emails;
  private final int maxAttempts;
  private final long backoffBaseMillis;
  private final long backoffCapMillis;
  private final int maxBatch;
  private final Duration staleAge;
  private final boolean enabled;

  public EmailOutboxWorker(EmailOutboxRepository outbox, NotificationService.EmailSender emails,
      @Value("${app.notifications.outbox.max-attempts:5}") int maxAttempts,
      @Value("${app.notifications.outbox.backoff-base-ms:1000}") long backoffBaseMillis,
      @Value("${app.notifications.outbox.backoff-cap-ms:600000}") long backoffCapMillis,
      @Value("${app.notifications.outbox.max-batch:20}") int maxBatch,
      @Value("${app.notifications.outbox.stale-after-s:60}") long staleAfterSeconds,
      @Value("${app.notifications.outbox.delivery-enabled:true}") boolean enabled) {
    this.outbox = outbox;
    this.emails = emails;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.backoffBaseMillis = backoffBaseMillis;
    this.backoffCapMillis = backoffCapMillis;
    this.maxBatch = maxBatch;
    this.staleAge = Duration.ofSeconds(staleAfterSeconds);
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${app.notifications.outbox.poll-ms:5000}",
      initialDelayString = "${app.notifications.outbox.initial-delay-ms:15000}")
  public int deliverDue() {
    if (!enabled) {
      return 0;
    }
    Instant now = Instant.now();
    // 1ms grace on the "due" test: a row whose stored next_attempt_at was
    // written as `now` can round a hair past it in the database (timestamp
    // precision), which would otherwise stall an immediately-retryable row
    // until the next poll. Real backoffs are >= 1s, so the grace never fires
    // early by anything material.
    Instant dueCutoff = now.plusMillis(1);
    int delivered = 0;
    while (delivered < maxBatch) {
      List<UUID> candidates = candidates(now, dueCutoff, maxBatch - delivered);
      boolean claimedAny = false;
      for (UUID id : candidates) {
        boolean claimed = outbox.claim(id, now, dueCutoff) == 1
            || outbox.reclaimStale(id, now, now.minus(staleAge)) == 1;
        if (!claimed) {
          continue;
        }
        claimedAny = true;
        deliver(id, now);
        delivered++;
      }
      if (!claimedAny) {
        break;
      }
    }
    if (delivered > 0) {
      log.info("Delivered {} queued email(s)", delivered);
    }
    return delivered;
  }

  private List<UUID> candidates(Instant now, Instant dueCutoff, int limit) {
    List<UUID> ids = new ArrayList<>(
        outbox.findPendingIds(dueCutoff, PageRequest.of(0, limit)));
    if (ids.size() < limit) {
      ids.addAll(outbox.findStaleDeliveringIds(now.minus(staleAge),
          PageRequest.of(0, limit - ids.size())));
    }
    return ids;
  }

  private void deliver(UUID id, Instant now) {
    EmailOutbox row = outbox.findById(id).orElse(null);
    if (row == null) {
      return;
    }
    try {
      emails.send(row.getEmail(), row.getSubject(), row.getBody());
      outbox.markSent(id, now);
    } catch (RuntimeException ex) {
      int attempts = row.getAttempts() + 1;
      boolean deadLetter = attempts >= maxAttempts;
      outbox.recordFailure(id, deadLetter ? EmailOutbox.Status.FAILED : EmailOutbox.Status.PENDING,
          attempts, now.plusMillis(backoffMillis(attempts)), redact(ex), now);
      log.warn("Email delivery attempt {} failed for {} ({}): {}",
          attempts, row.getEmail(), id, redact(ex));
    }
  }

  private long backoffMillis(int attempts) {
    long base = Math.min(backoffBaseMillis << Math.min(attempts - 1, 16), backoffCapMillis);
    return Math.max(backoffBaseMillis, base);
  }

  /**
   * Provider errors must never leak stack traces or internal details to the
   * queue: whitespace (including stack-frame tabs) collapses to a single
   * line and the message is capped, so deep frames and long internals fall
   * outside the retained window. Only the exception type plus the top of the
   * provider's own message survive.
   */
  static String redact(RuntimeException ex) {
    String message = ex.getMessage();
    if (message != null && !message.isBlank()) {
      message = message.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
      // The last_error column is 255 chars; leave room for the type prefix.
      if (message.length() > 200) {
        message = message.substring(0, 200);
      }
      return ex.getClass().getSimpleName() + ": " + message;
    }
    return ex.getClass().getSimpleName();
  }
}
