package com.kanban.modules.presence;

import com.kanban.common.json.Json;
import com.kanban.common.util.Dates;
import com.kanban.modules.events.EventsGateway;
import com.kanban.modules.presence.dto.PresenceStateDto;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectMemberRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * In-memory presence: per-user socket sets, project-room joins on connect, and
 * {@code presence:update} broadcasts to project rooms on online/offline transitions.
 */
@Service
public class PresenceService {
  private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

  private final EventsGateway eventsGateway;
  private final ProjectMemberRepository projectMemberRepository;

  private final Object lock = new Object();
  private final Map<String, Set<String>> connections = new HashMap<>();
  private final Map<String, Set<String>> userProjects = new HashMap<>();
  private final Map<String, CompletableFuture<Set<String>>> userProjectsPending = new HashMap<>();
  private final Map<String, String> lastChangedAt = new HashMap<>();

  public PresenceService(EventsGateway eventsGateway, ProjectMemberRepository projectMemberRepository) {
    this.eventsGateway = eventsGateway;
    this.projectMemberRepository = projectMemberRepository;
  }

  @Async("eventExecutor")
  @EventListener
  public void onConnectionOpened(WsConnectionOpenedEvent event) {
    handleConnectionOpened(event);
  }

  @Async("eventExecutor")
  @EventListener
  public void onConnectionClosed(WsConnectionClosedEvent event) {
    handleConnectionClosed(event);
  }

  public void handleConnectionOpened(WsConnectionOpenedEvent event) {
    String userId = event.userId();
    String socketId = event.socketId();
    boolean isFirstSocket;
    int size;
    CompletableFuture<Set<String>> pending;
    boolean owner = false;
    synchronized (lock) {
      Set<String> sockets = connections.computeIfAbsent(userId, k -> new LinkedHashSet<>());
      isFirstSocket = sockets.isEmpty();
      sockets.add(socketId);
      size = sockets.size();
      if (isFirstSocket) {
        lastChangedAt.put(userId, Dates.iso(Instant.now()));
        // Store the in-flight future BEFORE loading so concurrent connects for
        // the same user await the same query instead of racing past it.
        pending = new CompletableFuture<>();
        userProjectsPending.put(userId, pending);
        owner = true;
      } else {
        pending = userProjectsPending.get(userId);
      }
    }
    if (owner) {
      try {
        Set<String> set = new LinkedHashSet<>(projectMemberRepository.findProjectIdsByUserId(userId));
        synchronized (lock) {
          userProjects.put(userId, set);
        }
        pending.complete(set);
      } catch (RuntimeException e) {
        pending.completeExceptionally(e);
        throw e;
      }
    } else if (pending != null) {
      pending.join();
    }
    List<String> rooms;
    synchronized (lock) {
      Set<String> projectIds = userProjects.getOrDefault(userId, Set.of());
      rooms = new ArrayList<>();
      for (String id : projectIds) {
        rooms.add("project:" + id);
      }
    }
    if (rooms.isEmpty()) {
      return;
    }
    eventsGateway.getServer().joinRooms(socketId, rooms);
    if (isFirstSocket) {
      broadcastPresence(userId, true, size, rooms);
    }
  }

  public void handleConnectionClosed(WsConnectionClosedEvent event) {
    String userId = event.userId();
    String socketId = event.socketId();
    List<String> rooms;
    synchronized (lock) {
      Set<String> sockets = connections.get(userId);
      if (sockets == null || !sockets.contains(socketId)) {
        return;
      }
      sockets.remove(socketId);
      if (!sockets.isEmpty()) {
        return;
      }
      lastChangedAt.put(userId, Dates.iso(Instant.now()));
      Set<String> projectIds = userProjects.get(userId);
      connections.remove(userId);
      userProjects.remove(userId);
      userProjectsPending.remove(userId);
      if (projectIds == null || projectIds.isEmpty()) {
        return;
      }
      rooms = new ArrayList<>();
      for (String id : projectIds) {
        rooms.add("project:" + id);
      }
    }
    broadcastPresence(userId, false, 0, rooms);
  }

  public boolean isOnline(String userId) {
    return getConnectionCount(userId) > 0;
  }

  public int getConnectionCount(String userId) {
    synchronized (lock) {
      Set<String> sockets = connections.get(userId);
      return sockets == null ? 0 : sockets.size();
    }
  }

  public List<PresenceStateDto> getOnlineStates(List<String> userIds) {
    List<PresenceStateDto> out = new ArrayList<>();
    synchronized (lock) {
      for (String userId : userIds) {
        Set<String> sockets = connections.get(userId);
        int count = sockets == null ? 0 : sockets.size();
        out.add(new PresenceStateDto(userId, count > 0, count, lastChangedAt.get(userId)));
      }
    }
    return out;
  }

  private void broadcastPresence(String userId, boolean isOnline, int connectionCount, List<String> rooms) {
    Map<String, Object> payload = Json.map(
        "userId", userId,
        "isOnline", isOnline,
        "connectionCount", connectionCount,
        "timestamp", Dates.iso(Instant.now()));
    for (String room : rooms) {
      eventsGateway.getServer().emitToRoom(room, "presence:update", payload);
    }
  }
}
