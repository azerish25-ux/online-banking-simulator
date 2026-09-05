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

  /**
   * Scalars only - never returns a managed {@link Account} into the caller's
   * persistence context. Money flows must resolve the two account IDs this
   * way and let {@link #findByIdForUpdate} be the first entity read of the
   * rows: if an Account were loaded here first, the later FOR UPDATE would
   * lock the row but keep the stale in-context state, and the save would
   * overwrite a newer committed balance (see LedgerMovementService).
   */
  @Query("select a.userId from Account a where a.id = :id")
  Optional<UUID> findOwnerIdById(UUID id);

  @Query("select a.id from Account a where a.iban = :iban")
  Optional<UUID> findIdByIban(String iban);

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
