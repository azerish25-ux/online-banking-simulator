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

  @Query(value = "SELECT * FROM ("
      + "SELECT t.* FROM transactions t WHERE t.from_account_id = :accountId "
      + "UNION ALL "
      + "SELECT t.* FROM transactions t WHERE t.to_account_id = :accountId"
      + ") u WHERE u.created_at >= :from AND u.created_at < :to "
      + "ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset",
      nativeQuery = true)
  java.util.List<Transaction> historyPage(UUID accountId, java.time.Instant from, java.time.Instant to, int limit, int offset);

  @Query(value = "SELECT COUNT(*) FROM transactions t "
      + "WHERE (t.from_account_id = :accountId OR t.to_account_id = :accountId) "
      + "AND t.created_at >= :from AND t.created_at < :to",
      nativeQuery = true)
  long historyCount(UUID accountId, java.time.Instant from, java.time.Instant to);

  @Query("select t from Transaction t where t.createdAt >= :since order by t.createdAt asc")
  java.util.List<Transaction> findSince(java.time.Instant since);

}

