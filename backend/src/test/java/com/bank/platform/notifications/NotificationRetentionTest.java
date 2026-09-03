package com.bank.platform.notifications;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bank.platform.auth.AuthService;
import com.bank.platform.auth.User;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NotificationRetentionTest {

  @Autowired NotificationService notifications;
  @Autowired NotificationRepository repository;
  @Autowired AuthService authService;
  @Autowired JdbcTemplate jdbc;
  @Autowired jakarta.persistence.EntityManager entityManager;

  @Test
  void purgeRemovesOnlyExpired() {
    User user = authService.register("purge@example.com", "secret123", "Purge User");
    Notification fresh =
        notifications.notify(user.getId(), user.getEmail(), "X", "Fresh", "body");
    Notification old =
        notifications.notify(user.getId(), user.getEmail(), "X", "Old", "body");
    repository.flush(); // save() is lazy: force INSERTs before raw SQL
    int backdated = jdbc.update(
        "update notifications set created_at = ? where id = ?",
        Timestamp.from(Instant.now().minusSeconds(100L * 24 * 3600)),
        old.getId());
    assertEquals(1, backdated);

    long removed = notifications.purgeOld();
    // Bulk deletes bypass the persistence context: drop stale managed copies.
    entityManager.clear();

    assertEquals(1, removed);
    assertTrue(repository.findById(fresh.getId()).isPresent());
    assertTrue(repository.findById(old.getId()).isEmpty());
  }
}
