package com.kanban.modules.dashboard;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Redis cache for the project dashboard (JSP-44); independent of login rate limiting. */
@ConfigurationProperties("app.dashboard-cache")
public record DashboardCacheProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("60s") Duration ttl,
    @DefaultValue("kanban:local:dashboard") String keyPrefix) {
  public DashboardCacheProperties {
    if (ttl == null || ttl.compareTo(Duration.ofSeconds(1)) < 0 || keyPrefix == null || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("Dashboard cache requires a TTL of at least 1s and a non-blank key prefix");
    }
  }
}
