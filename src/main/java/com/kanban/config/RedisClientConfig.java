package com.kanban.config;

import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Lettuce settings shared by every Redis user: rate limiting, the dashboard cache, presence. */
@Configuration
public class RedisClientConfig {
  // Lettuce's default reconnect backoff grows to 30s, so after a long outage backends would
  // reconnect up to 30s apart and presence would stay paused that long (JSP-42).
  @Bean
  ClientResourcesBuilderCustomizer redisReconnectDelay() {
    return builder -> builder.reconnectDelay(
        Delay.exponential(Duration.ZERO, Duration.ofSeconds(1), 2, TimeUnit.MILLISECONDS));
  }
}
