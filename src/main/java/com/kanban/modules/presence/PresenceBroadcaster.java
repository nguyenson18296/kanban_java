package com.kanban.modules.presence;

import com.kanban.common.json.Json;
import com.kanban.common.util.Dates;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.project.ProjectMemberRepository;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends {@code presence:update} to every project room of a user whose presence changed. Rooms come
 * from the database because the user may have no socket on this backend (a reconciled crash).
 */
@Component
public class PresenceBroadcaster {
  private static final Logger log = LoggerFactory.getLogger(PresenceBroadcaster.class);

  private final ProjectMemberRepository projectMemberRepository;
  private final SocketServer socketServer;
  // One thread keeps events in transition order without making the heartbeat wait on PostgreSQL.
  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "presence-broadcast");
    t.setDaemon(true);
    return t;
  });

  public PresenceBroadcaster(ProjectMemberRepository projectMemberRepository, SocketServer socketServer) {
    this.projectMemberRepository = projectMemberRepository;
    this.socketServer = socketServer;
  }

  public void broadcast(PresenceTransition transition) {
    try {
      executor.execute(() -> send(transition));
    } catch (RejectedExecutionException e) {
      log.debug("Presence broadcast dropped during shutdown for user {}", transition.userId());
    }
  }

  void send(PresenceTransition transition) {
    try {
      Map<String, Object> payload = Json.map(
          "userId", transition.userId(),
          "isOnline", transition.online(),
          "connectionCount", transition.connectionCount(),
          "timestamp", Dates.iso(transition.changedAt()));
      for (String projectId : projectMemberRepository.findProjectIdsByUserId(transition.userId())) {
        socketServer.emitToRoom("project:" + projectId, "presence:update", payload);
      }
    } catch (RuntimeException e) {
      log.warn("Presence broadcast failed for user {} ({})", transition.userId(), e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  void stop() {
    executor.shutdownNow();
  }
}
