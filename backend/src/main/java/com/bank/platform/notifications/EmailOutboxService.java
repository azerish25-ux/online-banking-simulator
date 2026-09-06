package com.bank.platform.notifications;

import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commit-side half of F25: {@link #enqueue} writes the delivery INTENT in the
 * caller's transaction (the operation's own), so a rolled-back operation
 * never produces mail. Deduplication happens on the stable
 * {@code deliveryKey} (unique index): re-enqueuing the same logical event
 * returns the existing row instead of double-queuing. Delivery itself lives
 * in {@link EmailOutboxWorker}, which runs only after the commit.
 */
@Service
public class EmailOutboxService {

  private final EmailOutboxRepository outbox;

  public EmailOutboxService(EmailOutboxRepository outbox) {
    this.outbox = outbox;
  }

  @Transactional
  public EmailOutbox enqueue(UUID userId, String email, UUID notificationId,
      String deliveryKey, String subject, String body) {
    return outbox.findByDeliveryKey(deliveryKey).orElseGet(() -> {
      EmailOutbox row = new EmailOutbox(userId, email, notificationId, deliveryKey, subject, body);
      try {
        return outbox.save(row);
      } catch (DataIntegrityViolationException race) {
        // Another enqueue of the same logical event won the unique race.
        outbox.flush();
        return outbox.findByDeliveryKey(deliveryKey).orElseThrow(() -> race);
      }
    });
  }

  @Transactional(readOnly = true)
  public Page<EmailOutbox> list(EmailOutbox.Status status, Pageable pageable) {
    return status == null
        ? outbox.findAll(pageable)
        : outbox.findByStatusOrderByCreatedAtDesc(status, pageable);
  }

  /** Operator requeues a dead letter for another delivery attempt. */
  @Transactional
  public boolean requeue(UUID id) {
    return outbox.requeue(id, java.time.Instant.now()) == 1;
  }
}
