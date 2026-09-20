package com.kanban.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.config.RateLimitConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RateLimitConfigTest {
  @Test
  void disabledByDefaultNeedsNoRedisBean() {
    new ApplicationContextRunner().withUserConfiguration(RateLimitConfig.class).run(context -> {
      assertThat(context).hasNotFailed().doesNotHaveBean(RedisRateLimiter.class);
    });
  }

  @Test
  void explicitlyDisabledNeedsNoRedisBean() {
    new ApplicationContextRunner().withUserConfiguration(RateLimitConfig.class)
        .withPropertyValues("app.rate-limit.enabled=false")
        .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(RedisRateLimiter.class));
  }

  @ParameterizedTest
  @CsvSource({"::1,0:0:0:0:0:0:0:1", "::ffff:127.0.0.1,127.0.0.1",
      "2001:DB8::1,2001:db8:0:0:0:0:0:1", "face,unknown", "host.example,unknown",
      "127.0.0.1,127.0.0.1"})
  void normalizesNumericIpWithoutAcceptingHostnames(String input, String expected) {
    assertThat(RateLimitInterceptor.normalizeIp(input)).isEqualTo(expected);
  }
}
