package com.bank.platform.beneficiaries;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {
  List<Beneficiary> findByUserIdOrderByNickname(UUID userId);
  Optional<Beneficiary> findByIdAndUserId(UUID id, UUID userId);
  boolean existsByUserIdAndIban(UUID userId, String iban);
}
