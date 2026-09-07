package com.bank.platform.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, UUID> {

  boolean existsByAccountIdAndPeriod(UUID accountId, String period);

  /** The most recent completed accrual period for an account (YYYY-MM sorts lexically). */
  Optional<InterestAccrual> findTopByAccountIdOrderByPeriodDesc(UUID accountId);

  /** One account × period row, for asserting what a period actually posted. */
  Optional<InterestAccrual> findByAccountIdAndPeriod(UUID accountId, String period);
}
