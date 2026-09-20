package com.kanban.common.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
    @DefaultValue("10") int maxRequests,
    @DefaultValue("60s") Duration window,
    @DefaultValue("kanban:local:rate-limit") String keyPrefix) {
  public RateLimitProperties {
    if (maxRequests < 1 || window == null || window.toMillis() < 1
        || keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("Rate limit requires positive quota/window and a non-blank key prefix");
    }
  }
}
