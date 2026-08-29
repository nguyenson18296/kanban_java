package com.kanban.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Environment-driven settings; names mirror the Nest app's env vars. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String env, Jwt jwt, RefreshToken refreshToken, SocketIo socketIo) {
  public record Jwt(String secret, String expiresIn) {}

  public record RefreshToken(String expiresIn) {}

  public record SocketIo(int port, boolean enabled) {}

  public boolean isProduction() {
    return "production".equals(env);
  }
}
