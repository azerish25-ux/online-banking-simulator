package com.bank.platform.accounts;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, UUID> {
  List<Account> findByUserIdOrderByCreatedAtAsc(UUID userId);

  boolean existsByIban(String iban);

  long countByUserIdAndType(UUID userId, AccountType type);

  Optional<Account> findByIban(String iban);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select a from Account a where a.id = :id")
  Optional<Account> findByIdForUpdate(UUID id);

  /**
   * Interest candidates: the types that accrue, still active, not yet accrued
   * this month. Rows are locked (FOR UPDATE) so two overlapping accrual runs
   * cannot both read the same snapshot: the second run blocks, then re-checks
   * the WHERE clause on the first run's committed rows and skips them.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("""
      select a from Account a
      where a.type in :types
        and a.status = :status
        and (a.lastInterestAt is null or a.lastInterestAt < :monthStart)
      """)
  List<Account> findInterestCandidates(
      @Param("types") Collection<AccountType> types,
      @Param("status") AccountStatus status,
      @Param("monthStart") Instant monthStart);
}
