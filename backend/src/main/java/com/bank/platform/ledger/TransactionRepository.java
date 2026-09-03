package com.bank.platform.ledger;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>,
    org.springframework.data.jpa.repository.JpaSpecificationExecutor<Transaction> {

  Optional<Transaction> findByIdempotencyKey(String idempotencyKey);


  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) and t.createdAt >= :since order by t.createdAt asc")
  java.util.List<Transaction> findByAccountSince(UUID accountId, java.time.Instant since);
  @Query("select t from Transaction t where (t.fromAccountId = :accountId or t.toAccountId = :accountId) and t.createdAt >= :from and t.createdAt < :to order by t.createdAt desc")
  java.util.List<Transaction> statementRows(UUID accountId, java.time.Instant from, java.time.Instant to);

  @Query("select t from Transaction t where t.createdAt >= :since order by t.createdAt asc")
  java.util.List<Transaction> findSince(java.time.Instant since);

}

