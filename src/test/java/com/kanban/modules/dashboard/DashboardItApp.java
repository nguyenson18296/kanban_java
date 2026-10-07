package com.kanban.modules.dashboard;

import com.kanban.KanbanApplication;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.PropertySource;

/**
 * Boots the whole application against the dedicated IT PostgreSQL ({@code compose.postgres.yml}) and
 * Redis ({@code compose.redis.yml}) for the JSP-44 dashboard ITs. Every setting is passed explicitly and
 * the database must be on loopback and named {@code *_it}, so these tests cannot reach Supabase.
 */
final class DashboardItApp {
  static final String DB_HOST = System.getProperty("dashboard.it.db-host", "127.0.0.1");
  static final int DB_PORT = Integer.getInteger("dashboard.it.db-port", 55432);
  static final String DB_NAME = System.getProperty("dashboard.it.db-name", "kanban_it");
  static final String DB_USER = System.getProperty("dashboard.it.db-user", "kanban_it");
  static final String DB_PASSWORD = System.getProperty("dashboard.it.db-password", "kanban_it");
  static final String REDIS_HOST = System.getProperty("redis.it.host", "127.0.0.1");
  static final int REDIS_PORT = Integer.getInteger("redis.it.port", 6379);

  private DashboardItApp() {}

  /** A fresh Redis namespace per run; tests delete only the keys they know, never FLUSHDB. */
  static String keyPrefix() {
    return "kanban:jsp44-it:" + UUID.randomUUID();
  }

  static ConfigurableApplicationContext start(String keyPrefix, Duration cacheTtl, int redisPort) {
    // Checked BEFORE the context starts: Flyway migrates during startup.
    if (!(DB_HOST.equals("127.0.0.1") || DB_HOST.equals("localhost")) || !DB_NAME.endsWith("_it")) {
      throw new IllegalStateException(
          "Dashboard ITs run only against a loopback *_it database, not " + DB_HOST + "/" + DB_NAME);
    }
    ConfigurableApplicationContext app = new SpringApplicationBuilder(KanbanApplication.class).run(
        "--spring.config.location=classpath:/application.yml",
        "--spring.main.banner-mode=off", "--logging.level.root=WARN",
        "--server.address=127.0.0.1", "--server.port=0",
        "--spring.datasource.url=jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME
            + "?sslmode=disable&stringtype=unspecified",
        "--spring.datasource.username=" + DB_USER, "--spring.datasource.password=" + DB_PASSWORD,
        "--spring.datasource.hikari.maximum-pool-size=4",
        "--spring.flyway.enabled=true", "--spring.flyway.baseline-on-migrate=false",
        "--app.jwt.secret=jsp44-dashboard-it", "--app.socket-io.enabled=false",
        "--app.rate-limit.enabled=false",
        "--app.storage.access-key-id=", "--app.storage.secret-access-key=",
        "--spring.data.redis.host=" + REDIS_HOST, "--spring.data.redis.port=" + redisPort,
        "--spring.data.redis.username=", "--spring.data.redis.password=", "--spring.data.redis.database=0",
        "--app.dashboard-cache.enabled=true", "--app.dashboard-cache.ttl=" + cacheTtl.toMillis() + "ms",
        "--app.dashboard-cache.key-prefix=" + keyPrefix);
    for (PropertySource<?> source : app.getEnvironment().getPropertySources()) {
      if (source.getName().contains(".env")) {
        app.close();
        throw new IllegalStateException("A .env file leaked into the dashboard IT context: " + source.getName());
      }
    }
    return app;
  }
}
