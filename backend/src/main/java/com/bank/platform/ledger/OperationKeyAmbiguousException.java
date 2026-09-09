package com.bank.platform.ledger;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A key-only operation lookup matched more than one of the caller's own
 * operations (one per owned originating account: the key namespace is the
 * account, so the same key string can legitimately name a different
 * operation on two of the caller's accounts).
 *
 * <p>This is never resolved by silently picking an arbitrary row. The caller
 * must scope the lookup to the originating account (the account-scoped
 * namespace equals the database uniqueness namespace, so it can match at
 * most one operation) or use the bounded recovery list.
 */
public class OperationKeyAmbiguousException extends RuntimeException {

  public OperationKeyAmbiguousException(String key, List<Transaction> hits) {
    super("Idempotency key \"" + key + "\" matches " + hits.size()
        + " of your operations on different accounts ("
        + hits.stream()
            .map(hit -> (hit.getFromAccountId() != null
                ? hit.getFromAccountId()
                : hit.getToAccountId()) + ":" + hit.getKind() + ":" + hit.getStatus())
            .distinct()
            .collect(Collectors.joining(", "))
        + "). Retry the lookup with the originating accountId, or use the "
        + "recent-operations list to choose one.");
  }
}
