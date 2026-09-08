package com.bank.platform.common;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The one place the read-model caches are constructed.
 *
 * <p>Previously the policy lived as an unread string under {@code
 * app.cache.caffeine.spec} - Spring's cache auto-configuration only reads
 * {@code spring.cache.caffeine.spec}, so the summary/public-stats caches were
 * running unbounded and immortal while the YAML claimed a 5-minute TTL and a
 * 2000-entry cap. This config binds {@code app.cache.*} through a typed
 * properties object and hands a fully specified {@link Caffeine} builder to a
 * real {@link CaffeineCacheManager}; {@code CachePolicyTest} inspects the
 * instantiated policy so the setting can never drift from the runtime again.
 */
@Configuration
@EnableConfigurationProperties(LedgerCacheConfig.Properties.class)
public class LedgerCacheConfig {

  /** Typed {@code app.cache.*} binding (relaxed names: {@code maximum-size}, {@code expire-after-write}). */
  @ConfigurationProperties("app.cache")
  public record Properties(
      @DefaultValue("2000") long maximumSize,
      @DefaultValue("5m") Duration expireAfterWrite) {}

  @Bean
  CaffeineCacheManager cacheManager(Properties props) {
    CaffeineCacheManager manager = new CaffeineCacheManager();
    manager.setAllowNullValues(false);
    manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(props.maximumSize())
        .expireAfterWrite(props.expireAfterWrite()));
    return manager;
  }
}
