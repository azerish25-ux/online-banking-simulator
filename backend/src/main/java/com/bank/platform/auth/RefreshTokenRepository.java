package com.bank.platform.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  @Modifying
  @Query("update RefreshToken r set r.revoked = true where r.userId = :userId and r.revoked = false")
  int revokeAllByUserId(UUID userId);
}
