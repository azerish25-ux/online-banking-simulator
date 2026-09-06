package com.bank.platform.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionKindReviewRepository extends JpaRepository<TransactionKindReview, UUID> {

  List<TransactionKindReview> findAllByOrderByCreatedAtDesc();
}
