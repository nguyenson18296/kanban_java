package com.kanban.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ServiceUnavailableException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisRateLimiterTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final RedisRateLimiter limiter = new RedisRateLimiter(redis,
      new RateLimitProperties(10, Duration.ofSeconds(60), "kanban:test:rate-limit"));

  @ParameterizedTest
  @CsvSource({"0,0", "1,1", "999,1", "1000,1", "1001,2", "60000,60"})
  void quotaUsesNamespacedKeyAndRoundsRetryAfterUp(long milliseconds, long seconds) {
    when(redis.execute(any(RedisScript.class), eq(List.of("kanban:test:rate-limit:login:127.0.0.1")),
        eq("10"), eq("60000"))).thenReturn(milliseconds);
    assertThat(limiter.retryAfterSeconds("login", "127.0.0.1")).isEqualTo(seconds);
  }

  @Test
  void connectionFailureBecomesGeneric503() {
    when(redis.execute(any(RedisScript.class), any(List.class), any(), any()))
        .thenThrow(new RedisConnectionFailureException("private connection details"));
    assertThatThrownBy(() -> limiter.retryAfterSeconds("login", "127.0.0.1"))
        .isInstanceOf(ServiceUnavailableException.class)
        .hasMessage("Login temporarily unavailable. Please try again later.")
        .hasNoCause();
  }

  @Test
  void missingScriptResultFailsClosed() {
    when(redis.execute(any(RedisScript.class), any(List.class), any(), any())).thenReturn(null);
    assertThatThrownBy(() -> limiter.retryAfterSeconds("login", "127.0.0.1"))
        .isInstanceOf(ServiceUnavailableException.class);
  }
}
