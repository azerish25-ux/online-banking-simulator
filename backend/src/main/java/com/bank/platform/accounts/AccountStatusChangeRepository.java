package com.bank.platform.accounts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountStatusChangeRepository extends JpaRepository<AccountStatusChange, UUID> {

  /** Transitions up to (not including) {@code to}, oldest first: the day-walk input. */
  List<AccountStatusChange> findByAccountIdAndChangedAtLessThanOrderByChangedAtAsc(
      UUID accountId, Instant to);
}
