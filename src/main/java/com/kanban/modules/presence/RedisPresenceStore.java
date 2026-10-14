package com.kanban.modules.presence;

import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.common.util.Dates;
import com.kanban.modules.presence.dto.PresenceStateDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Presence state shared by every backend: per user, a sorted set of socket ids scored by expiry time
 * and a status hash. Each operation is one Lua script that reads Redis's clock, so online/offline
 * transitions happen exactly once across backends.
 */
@Component
public class RedisPresenceStore {
  private static final Logger log = LoggerFactory.getLogger(RedisPresenceStore.class);
  private static final int BATCH_SOCKETS = 200;
  @SuppressWarnings("rawtypes")
  private static final DefaultRedisScript<List> REGISTER = script("presence-register");
  @SuppressWarnings("rawtypes")
  private static final DefaultRedisScript<List> UNREGISTER = script("presence-unregister");
  @SuppressWarnings("rawtypes")
  private static final DefaultRedisScript<List> DUE = script("presence-due");
  @SuppressWarnings("rawtypes")
  private static final DefaultRedisScript<List> READ = script("presence-read");

  private final StringRedisTemplate redis;
  private final String prefix;
  private final String expiryKey;
  private final String ttlMs;

  public RedisPresenceStore(StringRedisTemplate redis, PresenceProperties properties) {
    this.redis = redis;
    this.prefix = properties.keyPrefix();
    this.expiryKey = prefix + ":expiry";
    this.ttlMs = Long.toString(properties.connectionTtl().toMillis());
  }

  @SuppressWarnings("rawtypes")
  private static DefaultRedisScript<List> script(String name) {
    DefaultRedisScript<List> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
    script.setResultType(List.class);
    return script;
  }

  /**
   * Sets each socket's expiry to now + TTL (also the heartbeat) and returns the users that went online.
   * Every user needs at least one socket. An empty map still reaches Redis, so it doubles as a probe.
   */
  public List<PresenceTransition> register(Map<String, List<String>> socketsByUser) {
    return call("register", () -> {
      List<PresenceTransition> out = new ArrayList<>();
      for (Map<String, List<String>> batch : batches(socketsByUser)) {
        out.addAll(transitions(run(REGISTER, batch, ttlMs), true));
      }
      return out;
    });
  }

  /**
   * Removes the given sockets plus any expired ones (an empty list only purges) and returns the users
   * left with no live socket.
   */
  public List<PresenceTransition> unregister(Map<String, List<String>> socketsByUser) {
    return call("unregister", () -> {
      List<PresenceTransition> out = new ArrayList<>();
      for (Map<String, List<String>> batch : batches(socketsByUser)) {
        out.addAll(transitions(run(UNREGISTER, batch), false));
      }
      return out;
    });
  }

  /** Users with an expired socket, to pass to {@link #unregister} with no socket ids. */
  public List<String> due(int limit) {
    return call("due", () -> {
      List<String> out = new ArrayList<>();
      for (Object userId : redis.execute(DUE, List.of(expiryKey), Integer.toString(limit))) {
        out.add((String) userId);
      }
      return out;
    });
  }

  public List<PresenceStateDto> read(List<String> userIds) {
    return call("read", () -> {
      List<String> keys = new ArrayList<>();
      for (String userId : userIds) {
        keys.add(socketsKey(userId));
        keys.add(statusKey(userId));
      }
      List<?> result = redis.execute(READ, keys);
      List<PresenceStateDto> out = new ArrayList<>();
      for (int i = 0; i < userIds.size(); i++) {
        int count = ((Long) result.get(2 * i)).intValue();
        long changedAt = (Long) result.get(2 * i + 1);
        out.add(new PresenceStateDto(userIds.get(i), count > 0, count,
            changedAt == 0 ? null : Dates.iso(Instant.ofEpochMilli(changedAt))));
      }
      return out;
    });
  }

  // A user's sockets never span two batches, so a transition reports the user's full count.
  private static List<Map<String, List<String>>> batches(Map<String, List<String>> socketsByUser) {
    List<Map<String, List<String>>> batches = new ArrayList<>();
    Map<String, List<String>> batch = new LinkedHashMap<>();
    int size = 0;
    for (Map.Entry<String, List<String>> entry : socketsByUser.entrySet()) {
      int weight = Math.max(1, entry.getValue().size());
      if (size > 0 && size + weight > BATCH_SOCKETS) {
        batches.add(batch);
        batch = new LinkedHashMap<>();
        size = 0;
      }
      batch.put(entry.getKey(), entry.getValue());
      size += weight;
    }
    batches.add(batch);
    return batches;
  }

  @SuppressWarnings("rawtypes")
  private List<?> run(DefaultRedisScript<List> script, Map<String, List<String>> batch, String... leadingArgs) {
    List<String> keys = new ArrayList<>(List.of(expiryKey));
    List<String> args = new ArrayList<>(List.of(leadingArgs));
    batch.forEach((userId, sockets) -> {
      keys.add(socketsKey(userId));
      keys.add(statusKey(userId));
      args.add(userId);
      args.add(Integer.toString(sockets.size()));
      args.addAll(sockets);
    });
    return redis.execute(script, keys, args.toArray());
  }

  private static List<PresenceTransition> transitions(List<?> result, boolean online) {
    List<PresenceTransition> out = new ArrayList<>();
    for (int i = 0; i < result.size(); i += 3) {
      out.add(new PresenceTransition((String) result.get(i), online, ((Long) result.get(i + 1)).intValue(),
          Instant.ofEpochMilli((Long) result.get(i + 2))));
    }
    return out;
  }

  private String socketsKey(String userId) {
    return prefix + ":user:" + userId + ":sockets";
  }

  private String statusKey(String userId) {
    return prefix + ":user:" + userId + ":status";
  }

  private <T> T call(String operation, Supplier<T> body) {
    try {
      return body.get();
    } catch (RuntimeException e) {
      // Log the failure type, never credentials or Redis URLs.
      log.warn("Presence Redis {} failed ({})", operation, e.getClass().getSimpleName());
      throw new ServiceUnavailableException("Presence temporarily unavailable. Please try again later.");
    }
  }
}
