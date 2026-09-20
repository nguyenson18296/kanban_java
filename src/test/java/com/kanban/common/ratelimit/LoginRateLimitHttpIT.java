package com.kanban.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.GlobalExceptionHandler;
import com.kanban.config.JacksonConfig;
import com.kanban.config.RateLimitConfig;
import com.kanban.config.WebMvcConfig;
import com.kanban.modules.auth.AuthController;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.dto.AuthResponseDto;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
import com.kanban.modules.user.UserRole;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Real HTTP + Tomcat + Redis; only the auth business service is mocked (no PostgreSQL). */
class LoginRateLimitHttpIT {
  private final List<ServletWebServerApplicationContext> apps = new ArrayList<>();
  private final List<String> prefixes = new ArrayList<>();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
  @Import({AuthController.class, WebMvcConfig.class, RateLimitConfig.class, JacksonConfig.class,
      GlobalExceptionHandler.class, JwtAuthInterceptor.class, ProjectRoleInterceptor.class})
  static class TestApplication {
    @Bean
    AuthService authService() {
      AuthService auth = mock(AuthService.class);
      when(auth.login(any(), any(), any())).thenReturn(new AuthResponseDto("at", "rt",
          new AuthResponseDto.AuthUserDto("u1", "a@b.co", "A", UserRole.QA, null)));
      return auth;
    }

    @Bean
    JwtService jwtService() { return mock(JwtService.class); }

    @Bean
    ProjectAccessService projectAccessService() { return mock(ProjectAccessService.class); }
  }

  private String namespace() {
    String prefix = "kanban:jav37-http-it:" + UUID.randomUUID();
    prefixes.add(prefix);
    return prefix;
  }

  private ServletWebServerApplicationContext start(String prefix, boolean enabled, int redisPort,
      String proxyRegex) {
    // Load production YAML for Redis timeouts and safe proxy defaults, but never
    // import the developer's .env or connect to their application database.
    var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestApplication.class).run(
        "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
        "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
        "--logging.level.root=ERROR", "--app.rate-limit.enabled=" + enabled,
        "--app.rate-limit.max-requests=10", "--app.rate-limit.window=60s",
        "--app.rate-limit.key-prefix=" + prefix,
        "--spring.data.redis.host=" + System.getProperty("redis.it.host", "127.0.0.1"),
        "--spring.data.redis.port=" + redisPort, "--spring.data.redis.username=",
        "--spring.data.redis.password=", "--spring.data.redis.database=0",
        "--server.tomcat.remoteip.internal-proxies=" + proxyRegex);
    apps.add(app);
    return app;
  }

  private HttpResponse<String> login(ServletWebServerApplicationContext app, String... headers) throws Exception {
    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
            + app.getWebServer().getPort() + "/api/auth/login"))
        .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"a@b.co\",\"password\":\"secret\"}"));
    if (headers.length > 0) request.headers(headers);
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  @AfterEach
  void stop() {
    apps.forEach(ServletWebServerApplicationContext::close);
    client.close();
    // Delete only the known test keys, never a shared database or wildcard keys.
    if (!prefixes.isEmpty()) {
      var connection = RedisRateLimiterIT.connection();
      try {
        var redis = new StringRedisTemplate(connection);
        for (String prefix : prefixes) {
          redis.delete(List.of(prefix + ":login:127.0.0.1", prefix + ":login:203.0.113.1",
              prefix + ":login:203.0.113.2", prefix + ":login:198.51.100.1"));
        }
      } finally {
        connection.destroy();
      }
    }
  }

  @Test
  void twoHttpBackendsAndRestartShareQuotaAndIgnoreSpoofedHeaders() throws Exception {
    String prefix = namespace();
    int port = Integer.getInteger("redis.it.port", 6379);
    var a = start(prefix, true, port, "(?!)");
    var b = start(prefix, true, port, "(?!)");
    var docs = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
        + a.getWebServer().getPort() + "/api/docs-json")).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(docs.statusCode()).isEqualTo(200);
    var responses = new com.fasterxml.jackson.databind.ObjectMapper().readTree(docs.body())
        .path("paths").path("/api/auth/login").path("post").path("responses");
    assertThat(responses.has("503")).isTrue();
    assertThat(responses.path("429").path("headers").has("Retry-After")).isTrue();
    for (int i = 0; i < 6; i++) assertThat(login(a).statusCode()).isEqualTo(200);
    for (int i = 0; i < 4; i++) assertThat(login(b).statusCode()).isEqualTo(200);
    var denied = login(b, "X-Forwarded-For", "203.0.113.1", "Forwarded", "for=203.0.113.2");
    assertThat(denied.statusCode()).isEqualTo(429);
    assertThat(Long.parseLong(denied.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 60L);
    assertThat(denied.body()).contains("\"statusCode\":429");
    verify(a.getBean(AuthService.class), times(6)).login(any(), any(), any());
    verify(b.getBean(AuthService.class), times(4)).login(any(), any(), any());
    b.close();
    apps.remove(b);
    var restarted = start(prefix, true, port, "(?!)");
    assertThat(login(restarted).statusCode()).isEqualTo(429);
    verifyNoInteractions(restarted.getBean(AuthService.class));
  }

  @Test
  void trustedProxyUsesRightmostUntrustedClientNotSpoofedLeftmostEntry() throws Exception {
    var app = start(namespace(), true, Integer.getInteger("redis.it.port", 6379), "127\\.0\\.0\\.1");
    for (int i = 0; i < 10; i++) {
      assertThat(login(app, "X-Forwarded-For", "198.51.100.1, 203.0.113.1").statusCode()).isEqualTo(200);
    }
    assertThat(login(app, "X-Forwarded-For", "198.51.100.99, 203.0.113.1").statusCode()).isEqualTo(429);
    assertThat(login(app, "X-Forwarded-For", "203.0.113.2").statusCode()).isEqualTo(200);
  }

  @Test
  void unavailableRedisFailsClosedAndDisabledModeStillLogsIn() throws Exception {
    // Reserve a non-Redis endpoint that accepts TCP but never replies, exercising
    // the configured timeout without stopping any running Redis service.
    try (var silentServer = new ServerSocket(0, 20, java.net.InetAddress.getLoopbackAddress())) {
      var enabled = start(namespace(), true, silentServer.getLocalPort(), "(?!)");
      long start = System.nanoTime();
      var response = login(enabled);
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
      assertThat(response.body()).contains("\"error\":\"Service Unavailable\"")
          .doesNotContain("Redis", "localhost", "password");
      verifyNoInteractions(enabled.getBean(AuthService.class));
      var disabled = start(namespace(), false, silentServer.getLocalPort(), "(?!)");
      for (int i = 0; i < 11; i++) assertThat(login(disabled).statusCode()).isEqualTo(200);
      assertThat(disabled.getBeansOfType(RedisRateLimiter.class)).isEmpty();
    }
  }
}
