package com.bank.platform.notifications;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
  private final EmailSender emails;

  public NotificationService(NotificationRepository notifications, EmailSender emails) {
    this.notifications = notifications;
    this.emails = emails;
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

  @Transactional
  public void markRead(UUID userId, UUID id) {
    Notification found = notifications.findByIdAndUserId(id, userId)
        .orElseThrow(() -> new NotificationNotFoundException(id));
    found.markRead();
    notifications.save(found);
  }
}
