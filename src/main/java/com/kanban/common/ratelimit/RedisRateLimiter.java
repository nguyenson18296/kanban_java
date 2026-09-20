package com.kanban.common.ratelimit;

import com.kanban.common.exception.ServiceUnavailableException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Shared fixed-window quota. Only Redis owns the counter and window clock. */
public class RedisRateLimiter {
  private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
  private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
  static {
    SCRIPT.setLocation(new ClassPathResource("redis/rate-limit.lua"));
    SCRIPT.setResultType(Long.class);
  }

  private final StringRedisTemplate redis;
  private final RateLimitProperties properties;

  public RedisRateLimiter(StringRedisTemplate redis, RateLimitProperties properties) {
    this.redis = redis;
    this.properties = properties;
  }

  /** Returns zero when allowed, otherwise Retry-After in whole seconds (rounded up). */
  public long retryAfterSeconds(String endpoint, String clientIp) {
    try {
      Long remainingMs = redis.execute(SCRIPT,
          List.of(properties.keyPrefix() + ":" + endpoint + ":" + clientIp),
          Integer.toString(properties.maxRequests()), Long.toString(properties.window().toMillis()));
      if (remainingMs == null || remainingMs < 0) {
        throw new IllegalStateException("Invalid rate limit script result");
      }
      return remainingMs / 1000 + (remainingMs % 1000 == 0 ? 0 : 1);
    } catch (RuntimeException e) {
      // Log the failure type, never credentials, Redis URLs or request bodies.
      log.error("Redis rate limit check unavailable ({})", e.getClass().getSimpleName());
      throw new ServiceUnavailableException("Login temporarily unavailable. Please try again later.");
    }
  }
}
