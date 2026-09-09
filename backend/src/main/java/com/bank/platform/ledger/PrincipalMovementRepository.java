package com.bank.platform.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrincipalMovementRepository extends JpaRepository<PrincipalMovement, UUID> {

  /** All movements within a pricing window, oldest first: the day walk input. */
  List<PrincipalMovement> findByAccountIdAndPostedAtGreaterThanEqualAndPostedAtLessThanOrderByPostedAtAsc(
      UUID accountId, Instant from, Instant to);

  boolean existsByAccountIdAndKind(UUID accountId, PrincipalKind kind);

  /** Movements before a window: the baseline from which the day walk starts. */
  @Query("select coalesce(sum(p.amount), 0) from PrincipalMovement p "
      + "where p.accountId = :accountId and p.postedAt < :before")
  Optional<BigDecimal> sumPostedBefore(@Param("accountId") UUID accountId,
      @Param("before") Instant before);

  /** The first baseline (CUTOVER or earliest) instant, to bound accrual work. */
  @Query("select min(p.postedAt) from PrincipalMovement p where p.accountId = :accountId")
  Optional<Instant> earliestPostedAt(@Param("accountId") UUID accountId);
}
