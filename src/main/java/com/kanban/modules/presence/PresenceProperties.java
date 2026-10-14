package com.kanban.modules.presence;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Redis-backed presence shared by every backend instance (JSP-42). */
@ConfigurationProperties("app.presence")
public record PresenceProperties(
    @DefaultValue("10s") Duration heartbeatInterval,
    @DefaultValue("30s") Duration connectionTtl,
    @DefaultValue("kanban:local:presence") String keyPrefix) {
  public PresenceProperties {
    // A TTL of three intervals lets a live socket survive two slow or failed heartbeats.
    if (heartbeatInterval == null || !heartbeatInterval.isPositive() || connectionTtl == null
        || connectionTtl.compareTo(heartbeatInterval.multipliedBy(3)) < 0 || keyPrefix == null
        || keyPrefix.isBlank()) {
      throw new IllegalArgumentException("Presence requires a positive heartbeat interval, a connection TTL "
          + "of at least 3x that interval and a non-blank key prefix");
    }
  }
}
