package com.kanban.modules.events;

import com.kanban.common.events.EventBus;
import com.kanban.common.json.Json;
import com.kanban.modules.events.guards.WsJwtGuard;
import com.kanban.modules.events.socket.SocketClient;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.user.User;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Port of the Socket.IO gateway: connection auth, per-user rooms
 * ({@code user:<id>}), mid-session token refresh and {@link #emitToUser}.
 */
@Component
public class EventsGateway {
  private static final Logger log = LoggerFactory.getLogger(EventsGateway.class);

  private final WsJwtGuard wsJwtGuard;
  private final EventBus eventBus;
  private volatile SocketServer server;

  public EventsGateway(WsJwtGuard wsJwtGuard, EventBus eventBus) {
    this.wsJwtGuard = wsJwtGuard;
    this.eventBus = eventBus;
  }

  public SocketServer getServer() {
    return server;
  }

  public void setServer(SocketServer server) {
    this.server = server;
  }

  public void afterInit() {
    log.info("WebSocket gateway initialized");
  }

  public void handleConnection(SocketClient client) {
    try {
      User user = wsJwtGuard.validateToken(client);
      client.data().put("user", user);
      String userId = user.getId();
      client.join("user:" + userId);
      client.emit("connection:established", Json.map("userId", userId));
      log.info("Client connected: {} (user: {})", client.getId(), userId);
      eventBus.emit(new WsConnectionOpenedEvent(userId, client.getId()));
    } catch (RuntimeException e) {
      client.emit("connection:error", Json.map("message", "Authentication failed"));
      client.disconnect(true);
    }
  }

  public void handleDisconnect(SocketClient client) {
    Object user = client.data() == null ? null : client.data().get("user");
    String userId = user instanceof User u ? u.getId() : null;
    log.info("Client disconnected: {} (user: {})", client.getId(), userId == null ? "unknown" : userId);
    if (userId != null) {
      eventBus.emit(new WsConnectionClosedEvent(userId, client.getId()));
    }
  }

  /** {@code @SubscribeMessage('token:refresh')} */
  public void handleTokenRefresh(SocketClient client, Map<String, Object> data) {
    try {
      // Set the new token on handshake so validateToken reads it
      if (client.handshake().auth() == null) {
        client.handshake().setAuth(new HashMap<>());
      }
      client.handshake().auth().put("token", data == null ? null : data.get("token"));
      User user = wsJwtGuard.validateToken(client);
      client.data().put("user", user);
      String userId = user.getId();
      // Re-join the user room (no-op if already in it, ensures consistency)
      client.join("user:" + userId);
      client.emit("token:refresh:success", Json.map());
      log.info("Token refreshed for client: {} (user: {})", client.getId(), userId);
    } catch (RuntimeException e) {
      client.emit("token:refresh:error", Json.map("message", "Token refresh failed"));
      client.disconnect(true);
    }
  }

  public void emitToUser(String userId, String event, Object data) {
    server.emitToRoom("user:" + userId, event, data);
  }
}
