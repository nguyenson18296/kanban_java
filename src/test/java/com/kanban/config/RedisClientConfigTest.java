package com.kanban.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisClientConfigTest {
  @Test
  @DisplayName("Redis reconnect attempts never wait more than 1s, however long the outage")
  void reconnectDelayIsCapped() {
    DefaultClientResources.Builder builder = DefaultClientResources.builder();
    new RedisClientConfig().redisReconnectDelay().customize(builder);
    ClientResources resources = builder.build();
    try {
      assertThat(resources.reconnectDelay().createDelay(1)).isLessThan(Duration.ofSeconds(1));
      assertThat(resources.reconnectDelay().createDelay(50)).isEqualTo(Duration.ofSeconds(1));
    } finally {
      resources.shutdown();
    }
  }
}
