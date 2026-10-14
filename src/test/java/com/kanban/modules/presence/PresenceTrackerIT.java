package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.kanban.modules.events.socket.SocketServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Opt-in: mvn -Predis-it verify. Each "backend" is a tracker with its own Redis connection and a fake
 * Socket.IO server; a TCP proxy cuts a backend off Redis. Short timings, unique key prefix, never FLUSHDB.
 */
class PresenceTrackerIT {
  private static final Duration INTERVAL = Duration.ofMillis(200);
  private static final Duration TTL = Duration.ofMillis(600);
  private static final String HOST = System.getProperty("redis.it.host", "127.0.0.1");
  private static final int PORT = Integer.getInteger("redis.it.port", 6379);

  private final String prefix = "kanban:jsp42-it:" + UUID.randomUUID();
  private final List<LettuceConnectionFactory> connections = new ArrayList<>();
  private final List<PresenceTracker> trackers = new ArrayList<>();
  private final List<ToggleProxy> proxies = new ArrayList<>();
  private StringRedisTemplate redis;
  private RedisPresenceStore reader;

  /** Socket.IO stand-in: a socket is connected until the test closes it. */
  static final class FakeSockets implements SocketServer {
    final Set<String> connected = ConcurrentHashMap.newKeySet();

    @Override
    public void emitToRoom(String room, String event, Object data) {}

    @Override
    public void joinRooms(String socketId, List<String> rooms) {}

    @Override
    public boolean isConnected(String socketId) {
      return connected.contains(socketId);
    }
  }

  private record Backend(PresenceTracker tracker, FakeSockets sockets, PresenceBroadcaster broadcaster) {
    void connect(String userId, String socketId) {
      sockets.connected.add(socketId);
      tracker.connected(userId, socketId);
    }
  }

  /** Forwards TCP to Redis; cut() drops every connection and refuses new ones until restore(). */
  static final class ToggleProxy implements AutoCloseable {
    private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private volatile boolean up = true;

    ToggleProxy() throws IOException {
      Thread.ofVirtual().start(() -> {
        while (!server.isClosed()) {
          try {
            Socket client = server.accept();
            if (!up) {
              client.close();
              continue;
            }
            Socket upstream = new Socket(HOST, PORT);
            open.add(client);
            open.add(upstream);
            pipe(client, upstream);
            pipe(upstream, client);
          } catch (IOException e) {
            // Proxy closed, or Redis refused the upstream connection.
          }
        }
      });
    }

    int port() {
      return server.getLocalPort();
    }

    private void pipe(Socket from, Socket to) {
      Thread.ofVirtual().start(() -> {
        try (from; to) {
          from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException e) {
          // Connection cut.
        }
      });
    }

    void cut() throws IOException {
      up = false;
      for (Socket socket : open) {
        socket.close();
      }
      open.clear();
    }

    void restore() {
      up = true;
    }

    @Override
    public void close() throws IOException {
      server.close();
      cut();
    }
  }

  @BeforeEach
  void connect() {
    redis = template(PORT);
    reader = new RedisPresenceStore(redis, properties());
  }

  @AfterEach
  void cleanUp() throws IOException {
    try {
      trackers.forEach(PresenceTracker::stop);
      redis.delete(redis.keys(prefix + ":*"));
    } finally {
      connections.forEach(LettuceConnectionFactory::destroy);
      for (ToggleProxy proxy : proxies) {
        proxy.close();
      }
    }
  }

  private PresenceProperties properties() {
    return new PresenceProperties(INTERVAL, TTL, prefix);
  }

  private StringRedisTemplate template(int port) {
    LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(HOST, port),
        LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(300)).build());
    factory.afterPropertiesSet();
    connections.add(factory);
    return new StringRedisTemplate(factory);
  }

  private ToggleProxy proxy() throws IOException {
    ToggleProxy proxy = new ToggleProxy();
    proxies.add(proxy);
    return proxy;
  }

  private Backend backend(int redisPort) {
    FakeSockets sockets = new FakeSockets();
    PresenceBroadcaster broadcaster = mock(PresenceBroadcaster.class);
    PresenceTracker tracker = new PresenceTracker(
        new RedisPresenceStore(template(redisPort), properties()), broadcaster, sockets, properties());
    tracker.start();
    trackers.add(tracker);
    return new Backend(tracker, sockets, broadcaster);
  }

  private int count(String userId) {
    return reader.read(List.of(userId)).get(0).connectionCount();
  }

  @Test
  @DisplayName("a killed backend's sockets expire and a peer reconciles them; a socket on the peer keeps the user online")
  void crashedBackendIsReconciled() throws Exception {
    ToggleProxy proxy = proxy();
    Backend a = backend(proxy.port());
    Backend b = backend(PORT);
    a.connect("u1", "a1");
    a.connect("u2", "a2");
    b.connect("u1", "b1");
    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 2 && count("u2") == 1);

    // From Redis's side a backend cut off for good is a backend killed with kill -9: no more heartbeats.
    proxy.cut();

    verify(b.broadcaster(), timeout(5000)).broadcast(argThat(t -> t.userId().equals("u2") && !t.online()));
    assertThat(reader.read(List.of("u1"))).singleElement().satisfies(state -> {
      assertThat(state.isOnline()).isTrue();
      assertThat(state.connectionCount()).isEqualTo(1);
    });
    verify(b.broadcaster(), never()).broadcast(argThat(t -> t.userId().equals("u1") && !t.online()));
    verify(a.broadcaster(), never()).broadcast(argThat(t -> !t.online()));
  }

  @Test
  @DisplayName("Redis lost for longer than the TTL: nobody broadcasts offline and every socket comes back")
  void outageNeverReportsOffline() throws Exception {
    ToggleProxy proxy = proxy();
    Backend a = backend(proxy.port());
    Backend b = backend(proxy.port());
    a.connect("u1", "a1");
    b.connect("u2", "b1");
    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 1 && count("u2") == 1);

    proxy.cut();
    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 0 && count("u2") == 0);
    Thread.sleep(TTL.toMillis());
    proxy.restore();

    await().atMost(Duration.ofSeconds(10)).until(() -> count("u1") == 1 && count("u2") == 1);
    // Wait past both backends' reconcile gate: still nothing may go offline.
    Thread.sleep(TTL.multipliedBy(3).toMillis());
    assertThat(count("u1")).isEqualTo(1);
    assertThat(count("u2")).isEqualTo(1);
    verify(a.broadcaster(), never()).broadcast(argThat(t -> !t.online()));
    verify(b.broadcaster(), never()).broadcast(argThat(t -> !t.online()));
  }

  @Test
  @DisplayName("keys lost in Redis come back on the next heartbeat")
  void dataLossIsRepaired() {
    Backend a = backend(PORT);
    a.connect("u1", "a1");
    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 1);

    redis.delete(redis.keys(prefix + ":*"));

    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 1);
    verify(a.broadcaster(), timeout(2000).times(2)).broadcast(argThat(PresenceTransition::online));
    verify(a.broadcaster(), never()).broadcast(argThat(t -> !t.online()));
  }

  @Test
  @DisplayName("graceful shutdown removes the backend's sockets at once")
  void gracefulShutdown() {
    Backend a = backend(PORT);
    a.connect("u1", "a1");
    await().atMost(Duration.ofSeconds(5)).until(() -> count("u1") == 1);
    a.tracker().stop();
    assertThat(reader.read(List.of("u1")).get(0).isOnline()).isFalse();
  }
}
