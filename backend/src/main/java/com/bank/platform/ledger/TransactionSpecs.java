package com.bank.platform.ledger;

import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

public final class TransactionSpecs {

  private TransactionSpecs() {}

  public static Specification<Transaction> filters(
      UUID accountId, Boolean flagged, Boolean reviewed, Instant from, Instant to) {
    return (root, query, cb) -> {
      List<Predicate> predicates = new ArrayList<>();
      if (accountId != null) {
        predicates.add(cb.or(
            cb.equal(root.get("fromAccountId"), accountId),
            cb.equal(root.get("toAccountId"), accountId)));
      }
      if (flagged != null) {
        predicates.add(cb.equal(root.get("flagged"), flagged));
      }
      if (reviewed != null) {
        predicates.add(cb.equal(root.get("reviewed"), reviewed));
      }
      if (from != null) {
        predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
      }
      if (to != null) {
        predicates.add(cb.lessThan(root.get("createdAt"), to));
      }
      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }
}
