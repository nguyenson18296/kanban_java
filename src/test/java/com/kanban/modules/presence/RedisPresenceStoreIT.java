package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.common.util.Dates;
import com.kanban.modules.presence.dto.PresenceStateDto;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Opt-in: mvn -Predis-it verify. Two connections play two backends; unique key prefix, never FLUSHDB. */
class RedisPresenceStoreIT {
  private static final Duration TTL = Duration.ofSeconds(60);
  private static final String HOST = System.getProperty("redis.it.host", "127.0.0.1");
  private static final int PORT = Integer.getInteger("redis.it.port", 6379);

  private final String prefix = "kanban:jsp42-it:" + UUID.randomUUID();
  private LettuceConnectionFactory connectionA;
  private LettuceConnectionFactory connectionB;
  private StringRedisTemplate redis;

  private static LettuceConnectionFactory connection(int port) {
    LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(HOST, port),
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build());
    factory.afterPropertiesSet();
    return factory;
  }

  @BeforeEach
  void connect() {
    connectionA = connection(PORT);
    connectionB = connection(PORT);
    redis = new StringRedisTemplate(connectionA);
  }

  @AfterEach
  void cleanUp() {
    try {
      redis.delete(redis.keys(prefix + ":*"));
    } finally {
      connectionA.destroy();
      connectionB.destroy();
    }
  }

  private RedisPresenceStore store(LettuceConnectionFactory connection, Duration ttl) {
    return new RedisPresenceStore(new StringRedisTemplate(connection),
        new PresenceProperties(ttl.dividedBy(3), ttl, prefix));
  }

  private static List<PresenceTransition> all(List<Future<List<PresenceTransition>>> results) throws Exception {
    List<PresenceTransition> out = new ArrayList<>();
    for (Future<List<PresenceTransition>> result : results) {
      out.addAll(result.get());
    }
    return out;
  }

  @Test
  @DisplayName("two tabs count 2, closing one leaves 1 and online, closing the last goes offline")
  void twoTabsOnTwoBackends() {
    RedisPresenceStore a = store(connectionA, TTL);
    RedisPresenceStore b = store(connectionB, TTL);

    List<PresenceTransition> online = a.register(Map.of("u1", List.of("tab-1")));
    assertThat(online).singleElement().satisfies(t -> {
      assertThat(t.userId()).isEqualTo("u1");
      assertThat(t.online()).isTrue();
      assertThat(t.connectionCount()).isEqualTo(1);
    });
    String onlineAt = Dates.iso(online.get(0).changedAt());
    assertThat(b.register(Map.of("u1", List.of("tab-2")))).isEmpty();
    assertThat(a.read(List.of("u1"))).containsExactly(new PresenceStateDto("u1", true, 2, onlineAt));

    assertThat(a.unregister(Map.of("u1", List.of("tab-1")))).isEmpty();
    assertThat(b.read(List.of("u1"))).containsExactly(new PresenceStateDto("u1", true, 1, onlineAt));

    List<PresenceTransition> offline = b.unregister(Map.of("u1", List.of("tab-2")));
    assertThat(offline).singleElement().satisfies(t -> {
      assertThat(t.online()).isFalse();
      assertThat(t.connectionCount()).isZero();
      assertThat(t.changedAt()).isAfterOrEqualTo(online.get(0).changedAt());
    });
    assertThat(a.read(List.of("u1")))
        .containsExactly(new PresenceStateDto("u1", false, 0, Dates.iso(offline.get(0).changedAt())));
    assertThat(redis.keys(prefix + ":*")).containsExactly(prefix + ":user:u1:status");
  }

  @Test
  @DisplayName("repeating a register or unregister changes nothing; unknown users read offline")
  void idempotent() {
    RedisPresenceStore store = store(connectionA, TTL);
    assertThat(store.register(Map.of("u1", List.of("tab-1")))).hasSize(1);
    assertThat(store.register(Map.of("u1", List.of("tab-1")))).isEmpty();
    assertThat(store.read(List.of("u1")).get(0).connectionCount()).isEqualTo(1);
    assertThat(store.unregister(Map.of("u1", List.of("tab-1")))).hasSize(1);
    assertThat(store.unregister(Map.of("u1", List.of("tab-1")))).isEmpty();
    assertThat(store.unregister(Map.of("ghost", List.of("tab-x")))).isEmpty();
    assertThat(store.read(List.of("ghost"))).containsExactly(new PresenceStateDto("ghost", false, 0, null));
  }

  @Test
  @DisplayName("100 concurrent connects then disconnects on two backends produce exactly one online and one offline")
  void concurrentTransitionsHappenOnce() throws Exception {
    RedisPresenceStore a = store(connectionA, TTL);
    RedisPresenceStore b = store(connectionB, TTL);
    try (var executor = Executors.newFixedThreadPool(12)) {
      List<Callable<List<PresenceTransition>>> connects = new ArrayList<>();
      List<Callable<List<PresenceTransition>>> disconnects = new ArrayList<>();
      for (int i = 0; i < 100; i++) {
        RedisPresenceStore target = i % 2 == 0 ? a : b;
        Map<String, List<String>> socket = Map.of("u1", List.of("tab-" + i));
        connects.add(() -> target.register(socket));
        disconnects.add(() -> target.unregister(socket));
      }

      assertThat(all(executor.invokeAll(connects))).singleElement()
          .satisfies(t -> assertThat(t.online()).isTrue());
      assertThat(b.read(List.of("u1")).get(0).connectionCount()).isEqualTo(100);

      assertThat(all(executor.invokeAll(disconnects))).singleElement()
          .satisfies(t -> assertThat(t.online()).isFalse());
      assertThat(a.read(List.of("u1")).get(0).isOnline()).isFalse();
    }
  }

  @Test
  @DisplayName("sockets of a crashed backend stop counting at once and are reconciled offline once")
  void expiredSocketsAreReconciled() {
    RedisPresenceStore crashed = store(connectionA, Duration.ofMillis(600));
    RedisPresenceStore alive = store(connectionB, TTL);
    PresenceTransition online = crashed.register(Map.of("u1", List.of("tab-a"))).get(0);
    crashed.register(Map.of("u2", List.of("tab-b")));
    alive.register(Map.of("u2", List.of("tab-c")));

    await().atMost(Duration.ofSeconds(3)).until(() -> !alive.read(List.of("u1")).get(0).isOnline());
    // Reads ignore the expired socket; lastChangedAt moves only when reconcile records the transition.
    assertThat(alive.read(List.of("u1")))
        .containsExactly(new PresenceStateDto("u1", false, 0, Dates.iso(online.changedAt())));
    assertThat(alive.read(List.of("u2")).get(0).connectionCount()).isEqualTo(1);
    assertThat(alive.due(100)).containsExactlyInAnyOrder("u1", "u2");

    Map<String, List<String>> purge = new LinkedHashMap<>();
    alive.due(100).forEach(user -> purge.put(user, List.of()));
    List<PresenceTransition> reconciled = alive.unregister(purge);
    assertThat(reconciled).singleElement().satisfies(t -> {
      assertThat(t.userId()).isEqualTo("u1");
      assertThat(t.online()).isFalse();
    });
    assertThat(alive.due(100)).isEmpty();
    assertThat(alive.unregister(Map.of("u1", List.of()))).isEmpty();
    assertThat(alive.read(List.of("u1")))
        .containsExactly(new PresenceStateDto("u1", false, 0, Dates.iso(reconciled.get(0).changedAt())));
    assertThat(redis.opsForZSet().range(prefix + ":user:u2:sockets", 0, -1)).containsExactly("tab-c");
  }

  @Test
  @DisplayName("heartbeats keep a socket alive past the TTL without touching lastChangedAt")
  void heartbeatKeepsChangedAt() throws Exception {
    RedisPresenceStore store = store(connectionA, Duration.ofMillis(900));
    PresenceTransition online = store.register(Map.of("u1", List.of("tab-1"))).get(0);
    for (int i = 0; i < 4; i++) {
      Thread.sleep(300);
      assertThat(store.register(Map.of("u1", List.of("tab-1")))).isEmpty();
    }
    assertThat(store.read(List.of("u1")))
        .containsExactly(new PresenceStateDto("u1", true, 1, Dates.iso(online.changedAt())));
    assertThat(store.due(10)).isEmpty();
  }

  @Test
  @DisplayName("large heartbeats are split into batches without splitting a user's sockets")
  void batches() {
    RedisPresenceStore store = store(connectionA, TTL);
    Map<String, List<String>> sockets = new LinkedHashMap<>();
    for (int i = 0; i < 199; i++) {
      sockets.put("u" + i, List.of("tab-" + i));
    }
    sockets.put("multi", List.of("tab-x", "tab-y", "tab-z"));
    for (int i = 199; i < 250; i++) {
      sockets.put("u" + i, List.of("tab-" + i));
    }

    List<PresenceTransition> online = store.register(sockets);
    assertThat(online).hasSize(251);
    assertThat(online).filteredOn(t -> t.userId().equals("multi")).singleElement()
        .satisfies(t -> assertThat(t.connectionCount()).isEqualTo(3));
    assertThat(store.register(sockets)).isEmpty();
    assertThat(store.unregister(sockets)).hasSize(251);
  }

  @Test
  @DisplayName("an empty register still reaches Redis; any Redis failure is the generic 503")
  void probeAndOutage() throws Exception {
    assertThat(store(connectionA, TTL).register(Map.of())).isEmpty();

    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    LettuceConnectionFactory down = connection(closedPort);
    try {
      RedisPresenceStore store = store(down, TTL);
      List<Runnable> calls = List.of(
          () -> store.register(Map.of()),
          () -> store.unregister(Map.of("u1", List.of("tab-1"))),
          () -> store.due(10),
          () -> store.read(List.of("u1")));
      for (Runnable call : calls) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ServiceUnavailableException.class, e -> {
          assertThat(e.getStatus()).isEqualTo(503);
          assertThat(e.toBody()).containsEntry("message", "Presence temporarily unavailable. Please try again later.");
          assertThat(e).hasNoCause();
        });
      }
    } finally {
      down.destroy();
    }
  }
}
