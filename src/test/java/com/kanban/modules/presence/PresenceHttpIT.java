package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.common.exception.GlobalExceptionHandler;
import com.kanban.config.JacksonConfig;
import com.kanban.config.WebMvcConfig;
import com.kanban.modules.auth.AuthService;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.auth.interfaces.JwtPayload;
import com.kanban.modules.events.EventsGateway;
import com.kanban.modules.presence.PresenceTrackerIT.FakeSockets;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMemberRepository;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import java.net.InetAddress;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Opt-in: mvn -Predis-it verify. Two backends (real Tomcat, real Redis, same key prefix) answer the
 * presence API with the same state; only auth, PostgreSQL and Socket.IO are replaced.
 */
class PresenceHttpIT {
  private static final String USER = "11111111-1111-4111-8111-111111111111";
  private static final String NEVER_CONNECTED = "22222222-2222-4222-8222-222222222222";
  private static final int PORT = Integer.getInteger("redis.it.port", 6379);
  private static final Duration FAST = Duration.ofMillis(200);

  private final List<ServletWebServerApplicationContext> apps = new ArrayList<>();
  private final List<String> prefixes = new ArrayList<>();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper json = new ObjectMapper();

  @Configuration(proxyBeanMethods = false)
  @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
      HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
  @EnableConfigurationProperties(PresenceProperties.class)
  @Import({PresenceController.class, PresenceService.class, PresenceTracker.class, PresenceBroadcaster.class,
      RedisPresenceStore.class, WebMvcConfig.class, JacksonConfig.class, GlobalExceptionHandler.class,
      JwtAuthInterceptor.class, ProjectRoleInterceptor.class})
  static class TestApplication {
    @Bean
    JwtService jwtService() {
      JwtService jwt = mock(JwtService.class);
      when(jwt.verify("tok")).thenReturn(new JwtPayload(USER, "a@b.co", "qa"));
      return jwt;
    }

    @Bean
    AuthService authService() {
      AuthService auth = mock(AuthService.class);
      when(auth.validateUserById(USER)).thenReturn(new User(USER, "a@b.co", "A", UserRole.QA, null, true));
      return auth;
    }

    @Bean
    ProjectAccessService projectAccessService() { return mock(ProjectAccessService.class); }

    @Bean
    ProjectMemberRepository projectMemberRepository() {
      ProjectMemberRepository repo = mock(ProjectMemberRepository.class);
      when(repo.findProjectIdsByUserId(any())).thenReturn(List.of());
      return repo;
    }

    @Bean
    EventsGateway eventsGateway() { return mock(EventsGateway.class); }

    @Bean
    FakeSockets sockets() { return new FakeSockets(); }
  }

  private String namespace() {
    String prefix = "kanban:jsp42-http-it:" + UUID.randomUUID();
    prefixes.add(prefix);
    return prefix;
  }

  private ServletWebServerApplicationContext start(String prefix, int redisPort, Duration heartbeat) {
    // Production YAML for Redis timeouts, but never the developer's .env or database.
    var app = (ServletWebServerApplicationContext) new SpringApplicationBuilder(TestApplication.class).run(
        "--spring.config.location=classpath:/application.yml", "--spring.config.import=",
        "--server.address=127.0.0.1", "--server.port=0", "--spring.main.banner-mode=off",
        "--logging.level.root=ERROR", "--app.presence.key-prefix=" + prefix,
        "--app.presence.heartbeat-interval=" + heartbeat.toMillis() + "ms",
        "--app.presence.connection-ttl=" + heartbeat.multipliedBy(3).toMillis() + "ms",
        "--spring.data.redis.host=" + System.getProperty("redis.it.host", "127.0.0.1"),
        "--spring.data.redis.port=" + redisPort, "--spring.data.redis.username=",
        "--spring.data.redis.password=", "--spring.data.redis.database=0");
    apps.add(app);
    return app;
  }

  private static void open(ServletWebServerApplicationContext app, String socketId) {
    app.getBean(FakeSockets.class).connected.add(socketId);
    app.getBean(PresenceService.class).handleConnectionOpened(new WsConnectionOpenedEvent(USER, socketId));
  }

  private static void close(ServletWebServerApplicationContext app, String socketId) {
    app.getBean(FakeSockets.class).connected.remove(socketId);
    app.getBean(PresenceService.class).handleConnectionClosed(new WsConnectionClosedEvent(USER, socketId));
  }

  private HttpResponse<String> get(ServletWebServerApplicationContext app, String path) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
        .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer tok").GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode me(ServletWebServerApplicationContext app) throws Exception {
    HttpResponse<String> response = get(app, "/api/presence/me");
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body());
  }

  @AfterEach
  void stop() {
    apps.forEach(ServletWebServerApplicationContext::close);
    client.close();
    if (!prefixes.isEmpty()) {
      var connection = new LettuceConnectionFactory(
          new RedisStandaloneConfiguration(System.getProperty("redis.it.host", "127.0.0.1"), PORT));
      connection.afterPropertiesSet();
      try {
        var redis = new StringRedisTemplate(connection);
        prefixes.forEach(prefix -> redis.delete(redis.keys(prefix + ":*")));
      } finally {
        connection.destroy();
      }
    }
  }

  @Test
  @DisplayName("two backends answer the presence API with the same shared state")
  void twoBackendsShareState() throws Exception {
    String prefix = namespace();
    var a = start(prefix, PORT, FAST);
    var b = start(prefix, PORT, FAST);
    open(a, "tab-on-a");
    open(b, "tab-on-b");

    await().atMost(Duration.ofSeconds(5)).until(() -> me(a).get("connectionCount").asInt() == 2);
    JsonNode online = me(a);
    assertThat(me(b)).isEqualTo(online);
    assertThat(online.get("isOnline").asBoolean()).isTrue();
    String onlineAt = online.get("lastChangedAt").asText();

    close(a, "tab-on-a");
    await().atMost(Duration.ofSeconds(5)).until(() -> me(b).get("connectionCount").asInt() == 1);
    assertThat(me(a)).isEqualTo(me(b));
    assertThat(me(a).get("isOnline").asBoolean()).isTrue();
    assertThat(me(a).get("lastChangedAt").asText()).isEqualTo(onlineAt);

    close(b, "tab-on-b");
    await().atMost(Duration.ofSeconds(5)).until(() -> !me(a).get("isOnline").asBoolean());
    JsonNode offline = me(b);
    assertThat(offline.get("connectionCount").asInt()).isZero();
    assertThat(offline.get("lastChangedAt").asText()).isGreaterThanOrEqualTo(onlineAt);

    HttpResponse<String> bulk = get(b, "/api/presence?userIds=" + USER + "," + NEVER_CONNECTED);
    assertThat(bulk.statusCode()).isEqualTo(200);
    assertThat(json.readTree(bulk.body())).isEqualTo(json.readTree("""
        {"items":[%s,{"userId":"%s","isOnline":false,"connectionCount":0,"lastChangedAt":null}]}
        """.formatted(offline, NEVER_CONNECTED)));

    JsonNode paths = json.readTree(get(a, "/api/docs-json").body()).path("paths");
    assertThat(paths.path("/api/presence").path("get").path("responses").has("503")).isTrue();
    assertThat(paths.path("/api/presence/me").path("get").path("responses").has("503")).isTrue();
  }

  @Test
  @DisplayName("stopping a backend removes its sockets at once, before its Redis connection closes")
  void shutdownUnregistersBeforeRedisCloses() throws Exception {
    String prefix = namespace();
    // A 30s TTL: only the shutdown unregister, not expiry, can lower the count right away.
    var a = start(prefix, PORT, Duration.ofSeconds(10));
    var b = start(prefix, PORT, Duration.ofSeconds(10));
    open(a, "tab-on-a");
    open(b, "tab-on-b");
    await().atMost(Duration.ofSeconds(5)).until(() -> me(b).get("connectionCount").asInt() == 2);

    a.close();
    apps.remove(a);
    assertThat(me(b).get("connectionCount").asInt()).isEqualTo(1);
  }

  @Test
  @DisplayName("an unreachable Redis is a quick, generic 503")
  void unavailableRedisIsGeneric503() throws Exception {
    // Accepts TCP but never replies, so the configured Redis timeout is what ends the request.
    try (var silentServer = new ServerSocket(0, 20, InetAddress.getLoopbackAddress())) {
      var app = start(namespace(), silentServer.getLocalPort(), FAST);
      long started = System.nanoTime();
      HttpResponse<String> response = get(app, "/api/presence/me");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      assertThat(response.statusCode()).isEqualTo(503);
      assertThat(json.readTree(response.body())).isEqualTo(json.readTree("""
          {"message":"Presence temporarily unavailable. Please try again later.",
           "error":"Service Unavailable","statusCode":503}
          """));
    }
  }
}
