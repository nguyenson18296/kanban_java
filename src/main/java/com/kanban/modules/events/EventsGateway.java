package com.kanban.modules.events;

import com.kanban.common.events.EventBus;
import com.kanban.common.exception.HttpException;
import com.kanban.common.json.Json;
import com.kanban.modules.events.guards.WsJwtGuard;
import com.kanban.modules.events.socket.SocketClient;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Port of the Socket.IO gateway: connection auth, per-user rooms
 * ({@code user:<id>}), membership-checked project board rooms ({@code project:<id>},
 * via {@code board:join} / {@code board:leave}), mid-session token refresh and
 * {@link #emitToUser} / {@link #emitToProject}.
 */
@Component
public class EventsGateway {
  private static final Logger log = LoggerFactory.getLogger(EventsGateway.class);

  private final WsJwtGuard wsJwtGuard;
  private final EventBus eventBus;
  private final ProjectAccessService projectAccessService;
  private volatile SocketServer server;

  public EventsGateway(WsJwtGuard wsJwtGuard, EventBus eventBus, ProjectAccessService projectAccessService) {
    this.wsJwtGuard = wsJwtGuard;
    this.eventBus = eventBus;
    this.projectAccessService = projectAccessService;
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

  /**
   * {@code @SubscribeMessage('board:join')} — subscribes the socket to {@code project:<id>}
   * once {@link ProjectAccessService#ensureRole} confirms membership (viewer+). The check
   * runs before the join, never after, and every failure gets the same generic reply so the
   * socket channel is not a membership oracle.
   */
  public void handleBoardJoin(SocketClient client, Map<String, Object> data) {
    Object userObj = client.data() == null ? null : client.data().get("user");
    String userId = userObj instanceof User u ? u.getId() : null;
    String projectId = projectIdOf(data);
    if (userId == null || projectId == null) {
      client.emit("board:join:error", boardJoinError(projectId));
      return;
    }
    try {
      projectAccessService.ensureRole(projectId, userId, ProjectRole.VIEWER);
    } catch (HttpException e) {
      // masked 404 / 403 from the gate: not a member
      client.emit("board:join:error", boardJoinError(projectId));
      return;
    } catch (RuntimeException e) {
      log.error("board:join membership check failed for client {} (user: {}, project: {})",
          client.getId(), userId, projectId, e);
      client.emit("board:join:error", boardJoinError(projectId));
      return;
    }
    client.join("project:" + projectId);
    client.emit("board:join:success", Json.map("projectId", projectId));
  }

  /** {@code @SubscribeMessage('board:leave')} */
  public void handleBoardLeave(SocketClient client, Map<String, Object> data) {
    String projectId = projectIdOf(data);
    if (projectId == null) {
      return;
    }
    client.leave("project:" + projectId);
    client.emit("board:leave:success", Json.map("projectId", projectId));
  }

  public void emitToUser(String userId, String event, Object data) {
    server.emitToRoom("user:" + userId, event, data);
  }

  public void emitToProject(String projectId, String event, Object data) {
    server.emitToRoom("project:" + projectId, event, data);
  }

  /**
   * The {@code projectId} of a board message as a non-blank String, or null. Anything else
   * the client sent (numbers, objects, blank strings) is treated as missing and is never
   * echoed back.
   */
  private static String projectIdOf(Map<String, Object> data) {
    Object value = data == null ? null : data.get("projectId");
    return value instanceof String s && !s.isBlank() ? s : null;
  }

  /** {@code projectId} is null unless the client sent a non-blank string (see {@link #projectIdOf}). */
  private static Map<String, Object> boardJoinError(String projectId) {
    return Json.map(
        "projectId", projectId,
        "message", "You do not have access to this project");
  }
}
