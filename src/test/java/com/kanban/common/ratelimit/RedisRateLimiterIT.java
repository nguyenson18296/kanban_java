package com.kanban.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Opt-in: mvn -Predis-it verify. Uses only unique test keys; never FLUSHDB. */
class RedisRateLimiterIT {
  private final String prefix = "kanban:jav37-it:" + UUID.randomUUID();
  private final List<String> keys = new ArrayList<>();
  private LettuceConnectionFactory connectionA;
  private LettuceConnectionFactory connectionB;
  private StringRedisTemplate redis;

  static LettuceConnectionFactory connection() {
    RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
        System.getProperty("redis.it.host", "127.0.0.1"), Integer.getInteger("redis.it.port", 6379));
    LettuceConnectionFactory factory = new LettuceConnectionFactory(config,
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    factory.afterPropertiesSet();
    return factory;
  }

  @BeforeEach
  void connect() {
    connectionA = connection();
    connectionB = connection();
    redis = new StringRedisTemplate(connectionA);
    try (var connection = connectionA.getConnection()) {
      assertThat(connection.ping()).isEqualTo("PONG");
    }
  }

  @AfterEach
  void cleanUp() {
    try {
      if (redis != null && !keys.isEmpty()) {
        redis.delete(keys);
      }
    } finally {
      if (connectionA != null) connectionA.destroy();
      if (connectionB != null) connectionB.destroy();
    }
  }

  private RedisRateLimiter limiter(LettuceConnectionFactory connection, int quota, Duration window) {
    return new RedisRateLimiter(new StringRedisTemplate(connection), new RateLimitProperties(quota, window, prefix));
  }

  private String key(String ip) {
    String key = prefix + ":login:" + ip;
    keys.add(key);
    return key;
  }

  @Test
  void twoInstancesAndRestartShareTheSameQuota() {
    String key = key("192.0.2.1");
    RedisRateLimiter a = limiter(connectionA, 10, Duration.ofSeconds(60));
    RedisRateLimiter b = limiter(connectionB, 10, Duration.ofSeconds(60));
    for (int i = 0; i < 6; i++) assertThat(a.retryAfterSeconds("login", "192.0.2.1")).isZero();
    for (int i = 0; i < 4; i++) assertThat(b.retryAfterSeconds("login", "192.0.2.1")).isZero();
    assertThat(a.retryAfterSeconds("login", "192.0.2.1")).isPositive();
    assertThat(b.retryAfterSeconds("login", "192.0.2.1")).isPositive();
    connectionB.destroy();
    connectionB = connection();
    assertThat(limiter(connectionB, 10, Duration.ofSeconds(60))
        .retryAfterSeconds("login", "192.0.2.1")).isPositive();
    assertThat(redis.opsForValue().get(key)).isEqualTo("10");
  }

  @Test
  void concurrentRequestsCannotOverspendQuota() throws Exception {
    String key = key("192.0.2.2");
    RedisRateLimiter a = limiter(connectionA, 10, Duration.ofSeconds(60));
    RedisRateLimiter b = limiter(connectionB, 10, Duration.ofSeconds(60));
    try (var executor = Executors.newFixedThreadPool(12)) {
      List<Callable<Long>> calls = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        RedisRateLimiter target = i % 2 == 0 ? a : b;
        calls.add(() -> target.retryAfterSeconds("login", "192.0.2.2"));
      }
      int allowed = 0;
      for (var result : executor.invokeAll(calls)) {
        if (result.get() == 0) allowed++;
      }
      assertThat(allowed).isEqualTo(10);
    }
    assertThat(redis.opsForValue().get(key)).isEqualTo("10");
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
  }

  @Test
  void expiryResetsQuotaAndDeniedRequestsDoNotExtendIt() {
    String key = key("192.0.2.3");
    key("192.0.2.4");
    RedisRateLimiter limiter = limiter(connectionA, 1, Duration.ofSeconds(2));
    assertThat(limiter.retryAfterSeconds("login", "192.0.2.3")).isZero();
    // Another IP has independent quota.
    assertThat(limiter.retryAfterSeconds("login", "192.0.2.4")).isZero();
    await().atMost(Duration.ofSeconds(2)).until(() -> redis.getExpire(key, TimeUnit.MILLISECONDS) < 1500);
    long ttlBefore = redis.getExpire(key, TimeUnit.MILLISECONDS);
    assertThat(limiter.retryAfterSeconds("login", "192.0.2.3")).isPositive();
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isLessThanOrEqualTo(ttlBefore);
    await().atMost(Duration.ofSeconds(3)).until(() -> !Boolean.TRUE.equals(redis.hasKey(key)));
    assertThat(limiter.retryAfterSeconds("login", "192.0.2.3")).isZero();
    assertThat(redis.opsForValue().get(key)).isEqualTo("1");
  }

  @Test
  void repairsPersistentCounterWithoutResettingUsage() {
    String key = key("192.0.2.5");
    redis.opsForValue().set(key, "10");
    assertThat(limiter(connectionA, 10, Duration.ofSeconds(60))
        .retryAfterSeconds("login", "192.0.2.5")).isPositive();
    assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 60000L);
    assertThat(redis.opsForValue().get(key)).isEqualTo("10");
  }
}
