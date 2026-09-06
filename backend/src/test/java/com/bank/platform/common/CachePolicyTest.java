package com.bank.platform.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * F07 - the cache policy that actually runs must be the policy that was
 * configured. The old {@code app.cache.caffeine.spec} string sat in a
 * namespace Spring never read, so the summaries/public-stats caches ran
 * unbounded and immortal while the YAML promised a 5-minute TTL and a
 * 2000-entry cap. LedgerCacheConfig now binds {@code app.cache.*} into a real
 * CaffeineCacheManager; this test inspects the INSTANTIATED Caffeine policy
 * (not the YAML) and proves expiry actually evicts under a short override.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.cache.expire-after-write=150ms")
class CachePolicyTest {

  @Autowired CacheManager cacheManager;

  @Test
  void theRunningCacheManagerCarriesTheConfiguredSizeAndExpiryPolicy() {
    assertTrue(cacheManager instanceof CaffeineCacheManager,
        "the typed binding must produce a real Caffeine manager");

    // CaffeineCacheManager creates a region on first use with the builder it
    // was given - pull the actual runtime policies out of the cache itself.
    for (String region : new String[] {"summaries", "public-stats"}) {
      Cache cache = cacheManager.getCache(region);
      assertNotNull(cache, region + " region must be creatable");
      @SuppressWarnings("unchecked")
      com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
          (com.github.benmanes.caffeine.cache.Cache<Object, Object>) cache.getNativeCache();
      assertTrue(nativeCache.policy().expireAfterWrite().isPresent(),
          region + " must expire after write");
      assertTrue(nativeCache.policy().eviction().isPresent(),
          region + " must carry a size bound");
    }
  }

  @Test
  void expiryActuallyEvictsRatherThanOnlyLookingConfigured() throws Exception {
    Cache cache = cacheManager.getCache("summaries");
    assertNotNull(cache);
    cache.put("expiry-probe", "value");
    assertNotNull(cache.get("expiry-probe"));
    // 150ms configured; wait well past it. Caffeine's time source is real
    // wall clock here (no injected ticker), so a generous margin is used.
    Thread.sleep(600);
    assertNull(cache.get("expiry-probe"), "entry must be gone after expireAfterWrite");
  }
}
