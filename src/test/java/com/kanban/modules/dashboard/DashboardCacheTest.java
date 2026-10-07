package com.kanban.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kanban.config.JacksonConfig;
import com.kanban.modules.dashboard.DashboardCache.Hit;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import com.kanban.modules.dashboard.dto.DashboardResponse.AssigneeCount;
import com.kanban.modules.dashboard.dto.DashboardResponse.ByStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** JSP-44: the Redis side of the dashboard cache-aside — keys, TTL, fail-open reads, after-commit eviction. */
class DashboardCacheTest {
  private static final String PROJECT = "UrzWUH3e";
  private static final String KEY = "kanban:test:dashboard:v1:project:UrzWUH3e";
  private static final DashboardResponse VALUE = new DashboardResponse(PROJECT, 3, 1,
      new ByStatus(2, 1, 0, 0, 0), List.of(new AssigneeCount("u1", 2)), 1, Instant.parse("2026-10-06T07:00:00.123Z"));

  /** The application's mapper: Boot's JavaTimeModule plus the JS date serializer from JacksonConfig. */
  private final ObjectMapper mapper = JsonMapper.builder()
      .addModule(new JavaTimeModule())
      .addModule(new JacksonConfig().jsDateModule())
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
      .build();

  private StringRedisTemplate redis;
  private ValueOperations<String, String> ops;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setUp() {
    redis = mock(StringRedisTemplate.class);
    ops = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(ops);
  }

  private DashboardCache cache(boolean enabled) {
    return new DashboardCache(redis, mapper,
        new DashboardCacheProperties(enabled, Duration.ofSeconds(60), "kanban:test:dashboard"));
  }

  @Nested
  class Read {
    @Test
    @DisplayName("a stored snapshot round-trips: SET with the TTL, then GET returns it unchanged (computed_at too)")
    void roundTrip() {
      DashboardCache cache = cache(true);
      cache.put(PROJECT, VALUE);

      ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
      verify(ops).set(eq(KEY), json.capture(), eq(Duration.ofSeconds(60)));
      when(ops.get(KEY)).thenReturn(json.getValue());

      assertThat(cache.get(PROJECT)).isEqualTo(new Hit(VALUE));
    }

    @Test
    @DisplayName("no key → MISS")
    void missingKey() {
      assertThat(cache(true).get(PROJECT)).isSameAs(DashboardCache.MISS);
    }

    @Test
    @DisplayName("an unreadable payload → MISS (recompute and overwrite), never an error")
    void corruptPayload() throws Exception {
      ObjectNode node = (ObjectNode) mapper.readTree(mapper.writeValueAsString(VALUE));
      String withoutByStatus = node.without("by_status").toString();
      for (String payload : List.of("not-json", "null", "{}", withoutByStatus)) {
        when(ops.get(KEY)).thenReturn(payload);
        assertThat(cache(true).get(PROJECT)).as(payload).isSameAs(DashboardCache.MISS);
      }
    }

    @Test
    @DisplayName("Redis failing on GET → SKIP (serve from PostgreSQL, do not write back)")
    void redisDownOnRead() {
      when(ops.get(KEY)).thenThrow(new RedisConnectionFailureException("down"));

      assertThat(cache(true).get(PROJECT)).isSameAs(DashboardCache.SKIP);
    }

    @Test
    @DisplayName("disabled → SKIP and no Redis call at all")
    void disabled() {
      DashboardCache cache = cache(false);

      assertThat(cache.get(PROJECT)).isSameAs(DashboardCache.SKIP);
      cache.put(PROJECT, VALUE);
      cache.evictAfterCommit(PROJECT);
      verifyNoInteractions(redis);
    }

    @Test
    @DisplayName("a failing SET is swallowed: the caller still gets its numbers")
    void putFailureSwallowed() {
      doThrow(new RedisConnectionFailureException("down")).when(ops).set(anyString(), anyString(), any(Duration.class));

      assertThatCode(() -> cache(true).put(PROJECT, VALUE)).doesNotThrowAnyException();
    }
  }

  @Nested
  class Evict {
    @AfterEach
    void clearSynchronization() {
      if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.clearSynchronization();
      }
    }

    @Test
    @DisplayName("outside a transaction: one DEL for every distinct project, nulls ignored")
    void deletesNowWithOneCommand() {
      cache(true).evictAfterCommit("p1", null, "p2", "p1");

      verify(redis).delete(List.of("kanban:test:dashboard:v1:project:p1", "kanban:test:dashboard:v1:project:p2"));
    }

    @Test
    @DisplayName("inside a transaction: nothing until afterCommit, then DEL")
    void waitsForCommit() {
      TransactionSynchronizationManager.initSynchronization();
      cache(true).evictAfterCommit("p1");
      verify(redis, never()).delete(anyCollection());

      TransactionSynchronizationUtils.triggerAfterCommit();

      verify(redis).delete(List.of("kanban:test:dashboard:v1:project:p1"));
    }

    @Test
    @DisplayName("inside a transaction that rolls back: no DEL")
    void rollbackDeletesNothing() {
      TransactionSynchronizationManager.initSynchronization();
      cache(true).evictAfterCommit("p1");

      TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

      verify(redis, never()).delete(anyCollection());
    }

    @Test
    @DisplayName("a failing DEL is swallowed: a committed write is never turned into an error")
    void deleteFailureSwallowed() {
      when(redis.delete(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));

      assertThatCode(() -> cache(true).evictAfterCommit("p1")).doesNotThrowAnyException();
    }
  }
}
