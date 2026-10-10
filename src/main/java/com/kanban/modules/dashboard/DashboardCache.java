package com.kanban.modules.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Redis side of the dashboard cache-aside (JSP-44): one JSON snapshot per project under
 * {@code <prefix>:v1:project:<projectId>}, written with its TTL in the same command and never extended by
 * reads. Redis is an optimization only: every failure is logged and the caller carries on with PostgreSQL.
 */
@Component
public class DashboardCache {
  private static final Logger log = LoggerFactory.getLogger(DashboardCache.class);
  /** Bump when {@link DashboardResponse} changes shape: old entries are then never read and simply expire. */
  private static final String VERSION = "v1";

  /** Result of a cache read. */
  sealed interface Lookup permits Hit, Miss, Skip {}

  record Hit(DashboardResponse value) implements Lookup {}

  /** No usable entry (absent or unreadable): compute, then store. */
  record Miss() implements Lookup {}

  /** Cache disabled or Redis failing: compute, and do not wait on Redis again to store. */
  record Skip() implements Lookup {}

  static final Lookup MISS = new Miss();
  static final Lookup SKIP = new Skip();

  private final StringRedisTemplate redis;
  private final ObjectMapper mapper;
  private final ObjectReader reader;
  private final DashboardCacheProperties properties;

  public DashboardCache(StringRedisTemplate redis, ObjectMapper mapper, DashboardCacheProperties properties) {
    this.redis = redis;
    this.mapper = mapper;
    // A payload missing any field (truncated, older shape) is unreadable, not a snapshot of zeros.
    this.reader = mapper.readerFor(DashboardResponse.class).with(
        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
        DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    this.properties = properties;
  }

  private String key(String projectId) {
    return properties.keyPrefix() + ":" + VERSION + ":project:" + projectId;
  }

  Lookup get(String projectId) {
    if (!properties.enabled()) {
      return SKIP;
    }
    String json;
    try {
      json = redis.opsForValue().get(key(projectId));
    } catch (RuntimeException e) {
      // Log the failure type only, never Redis URLs or credentials.
      log.warn("Dashboard cache read failed ({}); serving from PostgreSQL", e.getClass().getSimpleName());
      return SKIP;
    }
    if (json == null) {
      log.debug("Dashboard cache miss for project {}", projectId);
      return MISS;
    }
    try {
      DashboardResponse value = reader.readValue(json);
      if (value != null) {
        log.debug("Dashboard cache hit for project {}", projectId);
        return new Hit(value);
      }
    } catch (JsonProcessingException e) {
      // Fall through: recompute, and the fresh snapshot overwrites the bad entry.
    }
    log.warn("Dashboard cache entry for project {} is unreadable; recomputing", projectId);
    return MISS;
  }

  void put(String projectId, DashboardResponse value) {
    if (!properties.enabled()) {
      return;
    }
    try {
      // SET with the TTL in one command: the entry can never exist without an expiry.
      redis.opsForValue().set(key(projectId), mapper.writeValueAsString(value), properties.ttl());
    } catch (JsonProcessingException | RuntimeException e) {
      log.warn("Dashboard cache write failed ({})", e.getClass().getSimpleName());
    }
  }

  /**
   * Drops the cached snapshots of these projects. Call it once the change is committed: evicting earlier
   * lets a concurrent reader cache the old numbers again. Inside a transaction it therefore waits for
   * afterCommit (nothing on rollback); otherwise -- the task and column services, whose every save commits
   * on its own -- it runs now. Never throws: a cache failure must not fail a write that already succeeded.
   */
  public void evictAfterCommit(String... projectIds) {
    if (!properties.enabled()) {
      return;
    }
    List<String> keys = Arrays.stream(projectIds).filter(Objects::nonNull).distinct().map(this::key).toList();
    if (keys.isEmpty()) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          delete(keys);
        }
      });
    } else {
      delete(keys);
    }
  }

  private void delete(List<String> keys) {
    try {
      redis.delete(keys); // one DEL for every key
      log.debug("Dashboard cache evicted {} project(s)", keys.size());
    } catch (RuntimeException e) {
      log.warn("Dashboard cache eviction failed ({}); entries expire within the TTL", e.getClass().getSimpleName());
    }
  }
}
