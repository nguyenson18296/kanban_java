package com.kanban.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kanban.config.JacksonConfig;
import com.kanban.modules.dashboard.DashboardQueries.Snapshot;
import com.kanban.modules.dashboard.DashboardQueries.StatusRow;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import com.kanban.modules.task.TaskStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * JSP-44 consistency contract v1 against a real Redis ({@code compose.redis.yml}): a stale snapshot can
 * only live until its TTL, and reads never extend that TTL. PostgreSQL is replaced by a scripted
 * {@link DashboardQueries} so the race can be ordered deterministically. Unique keys only; never FLUSHDB.
 */
class DashboardCacheIT {
  private static final Duration TTL = Duration.ofSeconds(2);
  private static final String PROJECT = "Dash1234";

  private final String prefix = "kanban:jsp44-it:" + UUID.randomUUID();
  private final String key = prefix + ":v1:project:" + PROJECT;
  private LettuceConnectionFactory connection;
  private StringRedisTemplate redis;
  private DashboardCache cache;

  @BeforeEach
  void connect() {
    connection = new LettuceConnectionFactory(
        new RedisStandaloneConfiguration(DashboardItApp.REDIS_HOST, DashboardItApp.REDIS_PORT),
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    connection.afterPropertiesSet();
    redis = new StringRedisTemplate(connection);
    cache = new DashboardCache(redis,
        JsonMapper.builder().addModule(new JavaTimeModule()).addModule(new JacksonConfig().jsDateModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build(),
        new DashboardCacheProperties(true, TTL, prefix));
  }

  @AfterEach
  void cleanUp() {
    try {
      redis.delete(key);
    } finally {
      connection.destroy();
    }
  }

  /** "Committed data" is a version number: each snapshot reports it as its open-task count. */
  private static Snapshot snapshotOf(long version) {
    return new Snapshot(List.of(new StatusRow(TaskStatus.OPEN, version, 0, version)), List.of());
  }

  private long pttl() {
    return redis.getExpire(key, TimeUnit.MILLISECONDS);
  }

  @Test
  @DisplayName("a reader that loses the race to an eviction re-caches old numbers, but only until the TTL")
  void staleFillAfterEvictionIsBoundedByTtl() throws Exception {
    AtomicLong committed = new AtomicLong(1);
    AtomicInteger calls = new AtomicInteger();
    CountDownLatch readerQueried = new CountDownLatch(1);
    CountDownLatch writerDone = new CountDownLatch(1);
    DashboardService service = new DashboardService((projectId, asOf) -> {
      Snapshot seen = snapshotOf(committed.get());
      if (calls.incrementAndGet() == 1) {
        // The first reader has read the database and is about to store: let the writer run now.
        readerQueried.countDown();
        await().atMost(Duration.ofSeconds(5)).until(() -> writerDone.getCount() == 0);
      }
      return seen;
    }, cache);

    CompletableFuture<DashboardResponse> reader = CompletableFuture.supplyAsync(() -> service.get(PROJECT));
    assertThat(readerQueried.await(5, TimeUnit.SECONDS)).isTrue();
    committed.set(2);                 // the writer commits ...
    cache.evictAfterCommit(PROJECT);  // ... and evicts (nothing cached yet)
    writerDone.countDown();

    assertThat(reader.get(5, TimeUnit.SECONDS).total_tasks()).isEqualTo(1);
    // The losing reader stored version 1 after the eviction: stale, but with a TTL.
    assertThat(service.get(PROJECT).total_tasks()).isEqualTo(1);
    assertThat(pttl()).isPositive().isLessThanOrEqualTo(TTL.toMillis());
    // Once the TTL runs out the next read recomputes the committed numbers.
    await().atMost(TTL.plusSeconds(3)).pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> assertThat(service.get(PROJECT).total_tasks()).isEqualTo(2));
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("every stored snapshot has a TTL, and cache-hit reads never extend it")
  void readsNeverExtendTheTtl() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    DashboardService service = new DashboardService((projectId, asOf) -> {
      calls.incrementAndGet();
      return snapshotOf(1);
    }, cache);

    service.get(PROJECT);
    long first = pttl();
    assertThat(first).isPositive().isLessThanOrEqualTo(TTL.toMillis());

    Thread.sleep(500);
    for (int i = 0; i < 5; i++) {
      service.get(PROJECT);
    }

    assertThat(calls.get()).isEqualTo(1); // the five reads were hits
    assertThat(pttl()).isPositive().isLessThanOrEqualTo(first - 400);
  }
}
