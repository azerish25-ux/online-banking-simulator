package com.bank.platform.notifications;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications plus the outbox INTENT for external delivery. The
 * email stub is never called here: {@link #notify} records the in-app
 * row and commits a {@code email_outbox} row in the caller's transaction, and
 * {@link EmailOutboxWorker} calls the provider only after that commit. A
 * rolled-back operation therefore never sends mail, and a committed
 * operation cannot lose its mail to a crash before delivery.
 */
@Service
public class NotificationService {

  public interface EmailSender {
    void send(String toEmail, String subject, String body);
  }

  @Service
  public static class LoggingEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(String toEmail, String subject, String body) {
      log.info("[email-stub] to={} subject={} body={}", toEmail, subject, body);
    }
  }

  private final NotificationRepository notifications;
  private final EmailOutboxRepository outbox;
  private final Clock clock;
  private final long retentionDays;

  public NotificationService(NotificationRepository notifications, EmailOutboxRepository outbox,
      Clock clock,
      @Value("${app.notifications.retention-days:90}") long retentionDays) {
    this.notifications = notifications;
    this.outbox = outbox;
    this.clock = clock;
    this.retentionDays = retentionDays;
  }

  /**
   * Records the in-app notification AND commits the external-delivery intent
   * in the caller's transaction. The delivery identity is
   * type + recipient + notification, so re-queuing the same logical event
   * (an idempotent replay) inserts once.
   */
  @Transactional
  public Notification notify(UUID userId, String email, String type, String title, String body) {
    Notification saved = notifications.save(new Notification(userId, type, title, body));
    if (email != null) {
      outbox.save(new EmailOutbox(userId, email, saved.getId(),
          deliveryKey(type, userId, saved.getId()), title, body));
    }
    return saved;
  }

  /** Delivery identity for the outbox; the unique key dedupes re-enqueues. */
  static String deliveryKey(String type, UUID userId, UUID notificationId) {
    return type + ":" + userId + ":" + notificationId;
  }

  @Transactional(readOnly = true)
  public Page<Notification> mine(UUID userId, Pageable pageable) {
    return notifications.findByUserIdOrderByCreatedAtDesc(userId, pageable);
  }

  @Transactional(readOnly = true)
  public long unreadCount(UUID userId) {
    return notifications.countByUserIdAndReadFalse(userId);
  }

  @Scheduled(cron = "0 0 4 * * *")
  @Transactional
  public long purgeOld() {
    Instant cutoff = clock.instant().minusSeconds(retentionDays * 24 * 3600);
    long removed = notifications.deleteByCreatedAtBefore(cutoff);
    // Delivered mail is housekeeping too; FAILED (dead-letter) rows stay for
    // operator review until requeued or the retention window swallows them
    // with everything else an operator explicitly purges.
    removed += outbox.deleteSentBefore(cutoff);
    if (removed > 0) {
      LoggerFactory.getLogger(NotificationService.class)
          .info("Purged {} notifications/emails older than {} days", removed, retentionDays);
    }
    return removed;
  }

  @Transactional
  public Notification markRead(UUID userId, UUID id) {
    Notification found = notifications.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new NotificationNotFoundException(id));
    found.markRead();
    return notifications.save(found);
  }

  @Transactional
  public int markAllRead(UUID userId) {
    return notifications.markAllRead(userId);
  }
}
