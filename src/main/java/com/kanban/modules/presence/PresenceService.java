package com.kanban.modules.presence;

import com.kanban.modules.events.EventsGateway;
import com.kanban.modules.presence.dto.PresenceStateDto;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectMemberRepository;
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
 * Presence: joins this backend's sockets to their project rooms and hands every connect/disconnect to
 * {@link PresenceTracker}, which keeps the shared state in Redis; reads come from {@link RedisPresenceStore}.
 */
@Service
public class PresenceService {
  private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

  private final EventsGateway eventsGateway;
  private final ProjectMemberRepository projectMemberRepository;
  private final RedisPresenceStore presenceStore;
  private final PresenceTracker presenceTracker;

  private final Object lock = new Object();
  // This backend's sockets per user; they only decide how long the project-room cache lives.
  private final Map<String, Set<String>> connections = new HashMap<>();
  private final Map<String, Set<String>> userProjects = new HashMap<>();
  private final Map<String, CompletableFuture<Set<String>>> userProjectsPending = new HashMap<>();

  public PresenceService(EventsGateway eventsGateway, ProjectMemberRepository projectMemberRepository,
      RedisPresenceStore presenceStore, PresenceTracker presenceTracker) {
    this.eventsGateway = eventsGateway;
    this.projectMemberRepository = projectMemberRepository;
    this.presenceStore = presenceStore;
    this.presenceTracker = presenceTracker;
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
    try {
      joinProjectRooms(userId, socketId);
    } finally {
      // After the join, so the socket also receives its own user's online event.
      presenceTracker.connected(userId, socketId);
    }
  }

  private void joinProjectRooms(String userId, String socketId) {
    CompletableFuture<Set<String>> pending;
    boolean owner = false;
    synchronized (lock) {
      Set<String> sockets = connections.computeIfAbsent(userId, k -> new LinkedHashSet<>());
      boolean isFirstSocket = sockets.isEmpty();
      sockets.add(socketId);
      if (isFirstSocket) {
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
  }

  public void handleConnectionClosed(WsConnectionClosedEvent event) {
    String userId = event.userId();
    String socketId = event.socketId();
    synchronized (lock) {
      Set<String> sockets = connections.get(userId);
      if (sockets != null && sockets.remove(socketId) && sockets.isEmpty()) {
        connections.remove(userId);
        userProjects.remove(userId);
        userProjectsPending.remove(userId);
      }
    }
    presenceTracker.disconnected(userId, socketId);
  }

  public List<PresenceStateDto> getOnlineStates(List<String> userIds) {
    return presenceStore.read(userIds);
  }
}
