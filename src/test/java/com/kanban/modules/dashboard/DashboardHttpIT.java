package com.kanban.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.modules.auth.JwtService;
import com.kanban.modules.auth.interfaces.JwtPayload;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JSP-44 end to end: real HTTP, PostgreSQL ({@code compose.postgres.yml}) and Redis
 * ({@code compose.redis.yml}) — access checked before the cache, hits, TTL expiry, after-write eviction,
 * uncommitted data, corrupt entries and a Redis outage. Run: {@code mvn -Pdashboard-it verify}.
 */
class DashboardHttpIT {
  private static final Duration TTL = Duration.ofSeconds(3);
  private static final String PREFIX = DashboardItApp.keyPrefix();
  private static final List<String> projects = new ArrayList<>();
  private static final ObjectMapper json = new ObjectMapper();
  private static final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private static ConfigurableApplicationContext app;
  private static DashboardFixtures fx;
  private static StringRedisTemplate redis;

  @BeforeAll
  static void start() {
    app = DashboardItApp.start(PREFIX, TTL, DashboardItApp.REDIS_PORT);
    fx = new DashboardFixtures(app.getBean(JdbcTemplate.class));
    redis = app.getBean(StringRedisTemplate.class);
  }

  @AfterAll
  static void stop() {
    if (app != null) {
      // Delete only the keys this class could have written, never wildcard keys.
      redis.delete(projects.stream().map(DashboardHttpIT::key).toList());
      app.close();
    }
  }

  private static String key(String projectId) {
    return PREFIX + ":v1:project:" + projectId;
  }

  private static String project(String ownerId) {
    String id = fx.project(ownerId);
    projects.add(id);
    return id;
  }

  private static String token(ConfigurableApplicationContext ctx, String userId) {
    return ctx.getBean(JwtService.class).sign(new JwtPayload(userId, "it@example.com", "backend_developer"));
  }

  private static HttpResponse<String> send(ConfigurableApplicationContext ctx, String method, String path,
      String userId, String body) throws Exception {
    HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
            + ctx.getEnvironment().getProperty("local.server.port") + path))
        .timeout(Duration.ofSeconds(10))
        .header("Authorization", "Bearer " + token(ctx, userId))
        .method(method, body == null ? BodyPublishers.noBody() : BodyPublishers.ofString(body));
    if (body != null) {
      request.header("Content-Type", "application/json");
    }
    return client.send(request.build(), BodyHandlers.ofString());
  }

  private static JsonNode dashboard(ConfigurableApplicationContext ctx, String projectId, String userId)
      throws Exception {
    HttpResponse<String> response = send(ctx, "GET", "/api/projects/" + projectId + "/dashboard", userId, null);
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    return json.readTree(response.body());
  }

  @Test
  @DisplayName("a hit serves the stored snapshot (same computed_at, no recompute) until the TTL, then recomputes")
  void hitUntilTtlThenRecompute() throws Exception {
    String owner = fx.user();
    String project = project(owner);
    String task = fx.task(fx.column(project, false), "open", null);

    JsonNode first = dashboard(app, project, owner);
    assertThat(first.at("/by_status/open").asLong()).isEqualTo(1);
    long pttl = redis.getExpire(key(project), TimeUnit.MILLISECONDS);
    assertThat(pttl).isPositive().isLessThanOrEqualTo(TTL.toMillis());

    fx.setStatus(task, "done"); // straight to SQL: no eviction, so only the TTL can refresh the snapshot
    JsonNode second = dashboard(app, project, owner);
    assertThat(second).isEqualTo(first);

    await().atMost(TTL.plusSeconds(3)).pollInterval(Duration.ofMillis(250)).untilAsserted(() -> {
      JsonNode fresh = dashboard(app, project, owner);
      assertThat(fresh.at("/by_status/done").asLong()).isEqualTo(1);
      assertThat(fresh.at("/by_status/open").asLong()).isZero();
      assertThat(fresh.get("computed_at").asText()).isGreaterThan(first.get("computed_at").asText());
    });
  }

  @Test
  @DisplayName("API writes evict right away: create and PATCH status show up on the next read")
  void apiWritesEvict() throws Exception {
    String owner = fx.user();
    String project = project(owner);
    int column = fx.column(project, false);
    String task = fx.task(column, "open", null);
    assertThat(dashboard(app, project, owner).get("total_tasks").asLong()).isEqualTo(1);

    HttpResponse<String> created = send(app, "POST", "/api/tasks", owner,
        "{\"title\":\"new\",\"column_id\":" + column + "}");
    assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
    assertThat(dashboard(app, project, owner).get("total_tasks").asLong()).isEqualTo(2);

    HttpResponse<String> patched = send(app, "PATCH", "/api/tasks/" + task, owner, "{\"status\":\"done\"}");
    assertThat(patched.statusCode()).as(patched.body()).isEqualTo(200);
    JsonNode after = dashboard(app, project, owner);
    assertThat(after.at("/by_status/done").asLong()).isEqualTo(1);
    assertThat(after.at("/by_status/open").asLong()).isEqualTo(1);
  }

  @Test
  @DisplayName("a member removed from the project gets the masked 404 although their snapshot is still cached")
  void removedMemberCannotReadCachedSnapshot() throws Exception {
    String owner = fx.user();
    String viewer = fx.user();
    String outsider = fx.user();
    String project = project(owner);
    fx.member(project, viewer, "viewer");
    fx.task(fx.column(project, false), "open", null, viewer);
    assertThat(dashboard(app, project, viewer).get("total_tasks").asLong()).isEqualTo(1);

    HttpResponse<String> removed = send(app, "DELETE", "/api/projects/" + project + "/members", owner,
        "{\"user_ids\":[\"" + viewer + "\"]}");
    assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);

    assertThat(redis.hasKey(key(project))).isTrue();
    for (String caller : List.of(viewer, outsider)) {
      HttpResponse<String> denied = send(app, "GET", "/api/projects/" + project + "/dashboard", caller, null);
      assertThat(denied.statusCode()).isEqualTo(404);
      assertThat(json.readTree(denied.body()).get("message").asText())
          .isEqualTo("Project with id \"" + project + "\" not found");
    }
  }

  @Test
  @DisplayName("uncommitted changes never reach the cache: it holds committed numbers before and after a rollback")
  void uncommittedChangesNeverCached() throws Exception {
    String owner = fx.user();
    String project = project(owner);
    String task = fx.task(fx.column(project, false), "open", null);

    try (Connection tx = app.getBean(DataSource.class).getConnection()) {
      tx.setAutoCommit(false);
      try (var update = tx.prepareStatement(
          "UPDATE tasks SET status = 'done' WHERE id = CAST(? AS uuid)")) {
        update.setString(1, task);
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      // Cache miss while the change is pending: computed from committed rows only.
      assertThat(dashboard(app, project, owner).at("/by_status/open").asLong()).isEqualTo(1);
      tx.rollback();
    }

    JsonNode cached = dashboard(app, project, owner);
    assertThat(cached.at("/by_status/open").asLong()).isEqualTo(1);
    assertThat(cached.at("/by_status/done").asLong()).isZero();
  }

  @Test
  @DisplayName("an unreadable cache entry is recomputed from PostgreSQL and overwritten")
  void corruptEntryRecomputed() throws Exception {
    String owner = fx.user();
    String project = project(owner);
    fx.task(fx.column(project, false), "in_review", null);
    redis.opsForValue().set(key(project), "{not json", TTL);

    assertThat(dashboard(app, project, owner).at("/by_status/in_review").asLong()).isEqualTo(1);
    assertThat(json.readTree(redis.opsForValue().get(key(project))).get("project_id").asText()).isEqualTo(project);
  }

  @Test
  @DisplayName("Redis not answering: the dashboard still answers from PostgreSQL and task writes still succeed")
  void redisOutageFailsOpen() throws Exception {
    String owner = fx.user();
    String project = project(owner);
    String task = fx.task(fx.column(project, false), "open", null);
    // A TCP endpoint that accepts but never replies: the configured Redis timeouts apply.
    try (var silent = new ServerSocket(0, 20, InetAddress.getLoopbackAddress());
        var outage = DashboardItApp.start(DashboardItApp.keyPrefix(), TTL, silent.getLocalPort())) {
      long started = System.nanoTime();
      HttpResponse<String> read = send(outage, "GET", "/api/projects/" + project + "/dashboard", owner, null);
      assertThat(read.statusCode()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
      assertThat(read.body()).doesNotContain("Redis", "redis", "localhost");
      assertThat(json.readTree(read.body()).at("/by_status/open").asLong()).isEqualTo(1);

      started = System.nanoTime();
      HttpResponse<String> write = send(outage, "PATCH", "/api/tasks/" + task, owner, "{\"status\":\"done\"}");
      assertThat(write.statusCode()).as(write.body()).isEqualTo(200);
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));

      assertThat(dashboard(outage, project, owner).at("/by_status/done").asLong()).isEqualTo(1);
    }
  }
}
