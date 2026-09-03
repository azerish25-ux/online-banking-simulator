package com.bank.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
  org.springframework.data.domain.Page<AuditLog> findByAction(String action, org.springframework.data.domain.Pageable pageable);
}
