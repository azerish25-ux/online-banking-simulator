package com.bank.platform.ledger;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterestAccrualRepository extends JpaRepository<InterestAccrual, UUID> {

  boolean existsByAccountIdAndPeriod(UUID accountId, String period);
}
