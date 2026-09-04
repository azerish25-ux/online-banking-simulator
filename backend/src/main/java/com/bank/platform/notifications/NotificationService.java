package com.bank.platform.notifications;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications plus an email stub. The stub logs instead of sending;
 * swap {@link EmailSender} for an SMTP/Ses implementation when keys exist.
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
  private final long retentionDays;
  private final EmailSender emails;

  public NotificationService(NotificationRepository notifications, EmailSender emails,
      @Value("${app.notifications.retention-days:90}") long retentionDays) {
    this.notifications = notifications;
    this.emails = emails;
    this.retentionDays = retentionDays;
  }

  @Transactional
  public Notification notify(UUID userId, String email, String type, String title, String body) {
    Notification saved = notifications.save(new Notification(userId, type, title, body));
    if (email != null) {
      try {
        emails.send(email, title, body);
      } catch (RuntimeException ex) {
        LoggerFactory.getLogger(NotificationService.class)
            .warn("Email stub failed for {}: {}", email, ex.getMessage());
      }
    }
    return saved;
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
    long removed = notifications.deleteByCreatedAtBefore(
        java.time.Instant.now().minusSeconds(retentionDays * 24 * 3600));
    if (removed > 0) {
      org.slf4j.LoggerFactory.getLogger(NotificationService.class)
          .info("Purged {} notifications older than {} days", removed, retentionDays);
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
