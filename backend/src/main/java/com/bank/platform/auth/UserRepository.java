package com.bank.platform.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);

  /** Row lock for serializing per-user flows (e.g. refresh-token rotation). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> findByIdForUpdate(UUID id);

  /**
   * The authoritative read for every authentication transition: the user row
   * under the per-user write lock. Security state is validated and moved
   * ONLY on this locked read; an unlocked earlier snapshot is never authority.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.email = :email")
  Optional<User> findByEmailForUpdate(@Param("email") String email);
  boolean existsByEmail(String email);

  /** Legacy plaintext TOTP rows waiting for custody migration. */
  java.util.List<User> findByTotpKeyVersionAndTotpSecretIsNotNull(int totpKeyVersion);
  org.springframework.data.domain.Page<User> findByEmailContainingIgnoreCaseOrFullNameContainingIgnoreCase(String email, String fullName, org.springframework.data.domain.Pageable pageable);
}
