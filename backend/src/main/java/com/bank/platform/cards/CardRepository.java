package com.bank.platform.cards;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, UUID> {
  List<Card> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
  Optional<Card> findByIdAndUserId(UUID id, UUID userId);
}
