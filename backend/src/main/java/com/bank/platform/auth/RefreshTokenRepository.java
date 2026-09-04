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

  /**
   * Atomically consumes a presented token: marks it revoked only if it is
   * still usable. Returns 1 when this caller won the rotation, 0 when the
   * token was already consumed (concurrent use or a replay) - the caller then
   * burns the whole family. Doing the check-and-revoke in one UPDATE closes
   * the race where two parallel rotations both pass an in-Java revoked check
   * and mint separate valid sessions.
   */
  @Modifying
  @Query("update RefreshToken r set r.revoked = true where r.tokenHash = :tokenHash and r.revoked = false")
  int consume(String tokenHash);

  /**
   * Removes expired rows so the table cannot grow forever. Revoked rows are
   * deliberately kept until they expire: reuse detection needs to FIND a
   * revoked token to burn its whole family - deleting it early would let an
   * attacker's replay slide by as a harmless "unknown token".
   */
  @Modifying
  @Query("delete from RefreshToken r where r.expiresAt < :now")
  int purgeExpired(java.time.Instant now);
}
