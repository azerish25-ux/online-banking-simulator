package com.bank.platform.common;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Transaction-aware cache invalidation (F07).
 *
 * <p>Each clear is applied twice, deliberately:
 *
 * <ol>
 *   <li><b>Immediately</b> - a later read in the SAME transaction (test flows,
 *       or a service that reads after writing) must not observe a value cached
 *       before this transaction's changes became visible in it.</li>
 *   <li><b>At transaction completion (commit OR rollback)</b> - this closes
 *       the pre-commit race where a concurrent reader repopulates the cache
 *       with the pre-commit state between the immediate clear and the commit;
 *       the completion clear removes anything cached from a state that did not
 *       win, so no rolled-back or superseded result is left published. A value
 *       cached from a mid-transaction read is also purged when that
 *       transaction rolls back.</li>
 * </ol>
 *
 * <p>Honest consistency limit: this is a single-instance policy. Each app
 * instance invalidates its own caches around its own transactions; without a
 * shared invalidation bus two instances can serve each other's entries until
 * TTL expiry. That is the documented limit of this simulator deployment.
 */
@Component
public class LedgerCacheInvalidation {

  private final CacheManager cacheManager;

  public LedgerCacheInvalidation(CacheManager cacheManager) {
    this.cacheManager = cacheManager;
  }

  /** Clears the regions now and again when the current transaction completes. */
  public void clearSynchronized(String... regions) {
    clearNow(regions);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCompletion(int status) {
          clearNow(regions);
        }
      });
    }
  }

  /** Immediate clear - for callers that are already past every relevant commit. */
  public void clearNow(String... regions) {
    for (String region : regions) {
      org.springframework.cache.Cache cache = cacheManager.getCache(region);
      if (cache != null) {
        cache.clear();
      }
    }
  }
}
