package com.bank.platform.auth;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TotpEnrollmentRepository extends JpaRepository<TotpEnrollment, UUID> {

  /** The newest usable (unconsumed, unexpired) pending enrollment for a user. */
  @Query("select e from TotpEnrollment e where e.userId = :userId and e.consumed = false "
      + "and e.expiresAt > :now order by e.createdAt desc")
  List<TotpEnrollment> findUsable(@Param("userId") UUID userId, @Param("now") Instant now);

  @Modifying
  @Query("delete from TotpEnrollment e where e.userId = :userId and (e.consumed = true or e.expiresAt <= :now)")
  int purgeFinished(@Param("userId") UUID userId, @Param("now") Instant now);

  @Modifying
  @Query("delete from TotpEnrollment e where e.expiresAt < :now")
  int purgeExpired(@Param("now") Instant now);

  java.util.List<TotpEnrollment> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
