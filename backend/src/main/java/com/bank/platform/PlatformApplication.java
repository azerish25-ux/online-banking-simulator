package com.bank.platform;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class PlatformApplication {

  public static void main(String[] args) {
    SpringApplication.run(PlatformApplication.class, args);
  }

  /**
   * One injected clock for everything time-sensitive (challenge expiry,
   * posting timestamps, cache windows). Tests can override the bean to make
   * time deterministic instead of sleeping against real wall time.
   */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}


