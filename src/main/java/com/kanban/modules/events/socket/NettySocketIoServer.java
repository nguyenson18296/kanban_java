package com.kanban.modules.events.socket;

import com.corundumstudio.socketio.AuthTokenResult;
import com.corundumstudio.socketio.AuthorizationResult;
import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.HandshakeData;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kanban.config.AppProperties;
import com.kanban.modules.events.EventsGateway;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Socket.IO server (netty-socketio) adapter. Runs on its own port (Tomcat cannot
 * host the Socket.IO protocol) — see MIGRATION.md "Compatibility differences".
 *
 * <p>Socket.IO v4 sends the {@code auth} object in the CONNECT packet, which
 * netty-socketio exposes through an {@code AuthTokenListener}; that is where the
 * Nest {@code handleConnection} logic runs. Clients that connect without an
 * {@code auth} payload are validated after a short grace period (and rejected with
 * {@code connection:error}, as in Nest). Server-initiated disconnects are deferred
 * a few milliseconds so the preceding {@code connection:error} /
 * {@code token:refresh:error} event is flushed to the client first.
 */
@Component
public class NettySocketIoServer implements SocketServer {
  private static final Logger log = LoggerFactory.getLogger(NettySocketIoServer.class);
  private static final long NO_AUTH_GRACE_MS = 2000;
  private static final long DISCONNECT_DELAY_MS = 50;

  private final AppProperties props;
  private final EventsGateway gateway;
  private final ObjectMapper mapper;
  private final Map<UUID, NettySocketClient> clients = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "socketio-scheduler");
    t.setDaemon(true);
    return t;
  });
  private SocketIOServer server;

  public NettySocketIoServer(AppProperties props, EventsGateway gateway, ObjectMapper mapper) {
    this.props = props;
    this.gateway = gateway;
    this.mapper = mapper;
  }

  @PostConstruct
  public void start() {
    gateway.setServer(this);
    if (props.socketIo() == null || !props.socketIo().enabled()) {
      log.info("Socket.IO server disabled");
      return;
    }
    Configuration config = new Configuration();
    config.setPort(props.socketIo().port());
    config.setOrigin("*");
    config.setAuthorizationListener(data -> new AuthorizationResult(true));
    server = new SocketIOServer(config);

    // Engine.IO handshake done — no auth payload yet. Give the client a moment to
    // send its CONNECT packet with `auth`; if it never does, run the (failing)
    // connection handler so it receives `connection:error` and is disconnected.
    server.addConnectListener(client -> {
      NettySocketClient wrapped = wrap(client);
      scheduler.schedule(() -> {
        if (wrapped.authAttempted.compareAndSet(false, true)) {
          gateway.handleConnection(wrapped);
        }
      }, NO_AUTH_GRACE_MS, TimeUnit.MILLISECONDS);
    });

    // CONNECT packet with `auth` → the Nest handleConnection moment.
    server.getNamespace(com.corundumstudio.socketio.namespace.Namespace.DEFAULT_NAME)
        .addAuthTokenListener((authToken, client) -> {
      NettySocketClient wrapped = wrap(client);
      wrapped.handshake.setAuth(asMap(authToken));
      if (wrapped.authAttempted.compareAndSet(false, true)) {
        gateway.handleConnection(wrapped);
      }
      // Never reject the CONNECT itself: Nest accepts the socket, emits
      // `connection:error` and disconnects, which handleConnection already did.
      return AuthTokenResult.AuthTokenResultSuccess;
    });

    server.addDisconnectListener(client -> {
      NettySocketClient wrapped = clients.remove(client.getSessionId());
      gateway.handleDisconnect(wrapped != null ? wrapped : new NettySocketClient(client));
    });

    server.addEventListener("token:refresh", Object.class,
        (client, data, ack) -> gateway.handleTokenRefresh(wrap(client), asMap(data)));

    server.start();
    gateway.afterInit();
    log.info("Socket.IO server listening on port {}", props.socketIo().port());
  }

  @PreDestroy
  public void stop() {
    scheduler.shutdownNow();
    if (server != null) {
      server.stop();
    }
  }

  @Override
  public void emitToRoom(String room, String event, Object data) {
    if (server != null) {
      server.getRoomOperations(room).sendEvent(event, data);
    }
  }

  @Override
  public void joinRooms(String socketId, List<String> rooms) {
    if (server == null) {
      return;
    }
    SocketIOClient client = server.getClient(UUID.fromString(socketId));
    if (client != null) {
      client.joinRooms(new HashSet<>(rooms));
    }
  }

  private NettySocketClient wrap(SocketIOClient client) {
    return clients.computeIfAbsent(client.getSessionId(), id -> new NettySocketClient(client));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object data) {
    if (data instanceof Map<?, ?> m) {
      return new HashMap<>((Map<String, Object>) m);
    }
    if (data instanceof String s) {
      try {
        return mapper.readValue(s, Map.class);
      } catch (Exception e) {
        return new HashMap<>();
      }
    }
    return new HashMap<>();
  }

  /** Adapter over a netty-socketio client. */
  private final class NettySocketClient implements SocketClient {
    private final SocketIOClient client;
    private final Map<String, Object> data = new ConcurrentHashMap<>();
    private final InMemoryHandshake handshake;
    private final AtomicBoolean authAttempted = new AtomicBoolean(false);

    NettySocketClient(SocketIOClient client) {
      this.client = client;
      HandshakeData hd = client.getHandshakeData();
      Map<String, String> headers = new HashMap<>();
      HttpHeaders httpHeaders = hd.getHttpHeaders();
      if (httpHeaders != null) {
        for (Map.Entry<String, String> e : httpHeaders) {
          headers.put(e.getKey(), e.getValue());
        }
      }
      // socket.io initializes handshake.auth to {} when the client sent none
      this.handshake = new InMemoryHandshake(asMap(hd.getAuthToken()), headers);
    }

    @Override
    public String getId() {
      return client.getSessionId().toString();
    }

    @Override
    public Map<String, Object> data() {
      return data;
    }

    @Override
    public Handshake handshake() {
      return handshake;
    }

    @Override
    public void join(String room) {
      client.joinRoom(room);
    }

    @Override
    public void emit(String event, Object payload) {
      client.sendEvent(event, payload);
    }

    @Override
    public void disconnect(boolean close) {
      // let the event written just before this call reach the client first
      scheduler.schedule(client::disconnect, DISCONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }
  }
}
