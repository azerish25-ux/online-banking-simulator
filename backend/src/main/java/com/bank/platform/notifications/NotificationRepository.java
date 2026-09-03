package com.bank.platform.notifications;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
  Page<Notification> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
  Optional<Notification> findByIdAndUserId(UUID id, UUID userId);
  long countByUserIdAndReadFalse(UUID userId);

  @Modifying
  @Query("delete from Notification n where n.createdAt < :cutoff")
  int deleteByCreatedAtBefore(java.time.Instant cutoff);
}
