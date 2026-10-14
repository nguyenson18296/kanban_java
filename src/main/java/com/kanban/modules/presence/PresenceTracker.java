package com.kanban.modules.presence;

import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.modules.events.socket.SocketServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps this backend's sockets in {@link RedisPresenceStore}: registers them on connect, refreshes them
 * every heartbeat interval, unregisters them on disconnect, and reconciles sockets that another backend
 * stopped refreshing (it crashed). One worker thread runs all of this instance's Redis writes, so a
 * heartbeat never races the disconnect of the same socket.
 *
 * <p>A Redis error never produces an offline event: while Redis is unavailable the tracker only keeps
 * its socket list, retries every second, and re-registers every socket once Redis answers again.
 */
@Component
public class PresenceTracker implements SmartLifecycle {
  private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);
  private static final long UNHEALTHY_RETRY_MS = 1000;
  static final int RECONCILE_BATCH = 200;

  private final RedisPresenceStore store;
  private final PresenceBroadcaster broadcaster;
  private final SocketServer socketServer;
  private final long intervalMs;
  private final long reconcileAfterTicks;
  private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1, r -> {
    Thread t = new Thread(r, "presence-worker");
    t.setDaemon(true);
    return t;
  });

  // Worker-thread state; the registry maps this backend's socket ids to their user ids.
  private final Map<String, String> registry = new HashMap<>();
  private boolean healthy = true;
  private long healthyTicks;
  private volatile boolean closing;
  private volatile boolean running;

  public PresenceTracker(RedisPresenceStore store, PresenceBroadcaster broadcaster, SocketServer socketServer,
      PresenceProperties properties) {
    this.store = store;
    this.broadcaster = broadcaster;
    this.socketServer = socketServer;
    this.intervalMs = properties.heartbeatInterval().toMillis();
    // Reconcile only after a full TTL of healthy ticks, so peers recovering from the same Redis
    // outage re-register their sockets before anything expired is purged.
    this.reconcileAfterTicks = Math.ceilDiv(properties.connectionTtl().toMillis(), intervalMs) + 1;
    worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @Override
  public void start() {
    running = true;
    schedule(0);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  // LettuceConnectionFactory stops in phase 0: stopping first lets the final unregister reach Redis.
  @Override
  public int getPhase() {
    return 1;
  }

  public void connected(String userId, String socketId) {
    submit(() -> handleConnected(userId, socketId));
  }

  public void disconnected(String userId, String socketId) {
    submit(() -> handleDisconnected(userId, socketId));
  }

  void handleConnected(String userId, String socketId) {
    // Events arrive in any order: the socket may already be gone and its close handled.
    if (closing || !socketServer.isConnected(socketId)) {
      return;
    }
    registry.put(socketId, userId);
    if (healthy) {
      apply(() -> store.register(Map.of(userId, List.of(socketId))));
    }
  }

  void handleDisconnected(String userId, String socketId) {
    // token:refresh can change the socket's user; Redis knows the user it was registered under.
    String owner = registry.remove(socketId);
    if (healthy) {
      apply(() -> store.unregister(Map.of(owner != null ? owner : userId, List.of(socketId))));
    }
  }

  /** One heartbeat: drop closed sockets, refresh the rest, then reconcile expired ones. */
  void tick() {
    Map<String, String> closed = new HashMap<>();
    for (Iterator<Map.Entry<String, String>> it = registry.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, String> socket = it.next();
      if (!socketServer.isConnected(socket.getKey())) {
        closed.put(socket.getKey(), socket.getValue());
        it.remove();
      }
    }
    if (healthy && !closed.isEmpty() && !apply(() -> store.unregister(byUser(closed)))) {
      return;
    }
    // With no sockets this is an empty call that still tells whether Redis answers.
    if (!apply(() -> store.register(byUser(registry)))) {
      return;
    }
    healthyTicks++;
    if (healthyTicks >= reconcileAfterTicks) {
      reconcile();
    }
  }

  private void reconcile() {
    List<String> due;
    do {
      try {
        due = store.due(RECONCILE_BATCH);
      } catch (ServiceUnavailableException e) {
        markUnhealthy();
        return;
      }
      Map<String, List<String>> expired = new LinkedHashMap<>();
      due.forEach(userId -> expired.put(userId, List.of()));
      if (!expired.isEmpty() && !apply(() -> store.unregister(expired))) {
        return;
      }
    } while (due.size() == RECONCILE_BATCH);
  }

  /** Runs one store write and broadcasts its transitions; false when Redis failed. */
  private boolean apply(Supplier<List<PresenceTransition>> write) {
    List<PresenceTransition> transitions;
    try {
      transitions = write.get();
    } catch (ServiceUnavailableException e) {
      markUnhealthy();
      return false;
    }
    if (!healthy) {
      healthy = true;
      log.info("Presence resumed: Redis answers again, {} local socket(s) re-registered", registry.size());
    }
    if (!closing) {
      transitions.forEach(broadcaster::broadcast);
    }
    return true;
  }

  private void markUnhealthy() {
    healthyTicks = 0;
    if (healthy) {
      healthy = false;
      log.warn("Presence paused: Redis unavailable, keeping {} local socket(s) and retrying", registry.size());
    }
  }

  private static Map<String, List<String>> byUser(Map<String, String> sockets) {
    Map<String, List<String>> out = new HashMap<>();
    sockets.forEach((socketId, userId) -> out.computeIfAbsent(userId, k -> new ArrayList<>()).add(socketId));
    return out;
  }

  private void schedule(long delayMs) {
    try {
      worker.schedule(() -> {
        try {
          if (!closing) {
            tick();
          }
        } catch (RuntimeException e) {
          log.error("Presence heartbeat failed", e);
        } finally {
          if (!closing) {
            schedule(healthy ? intervalMs : Math.min(intervalMs, UNHEALTHY_RETRY_MS));
          }
        }
      }, delayMs, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException e) {
      log.debug("Presence heartbeat stopped: shutting down");
    }
  }

  private void submit(Runnable task) {
    try {
      worker.execute(() -> {
        try {
          task.run();
        } catch (RuntimeException e) {
          log.error("Presence update failed", e);
        }
      });
    } catch (RejectedExecutionException e) {
      log.debug("Presence update dropped: shutting down");
    }
  }

  /** Graceful shutdown: removes this backend's sockets now instead of letting them expire. */
  @Override
  public void stop() {
    closing = true;
    submit(() -> {
      if (healthy && !registry.isEmpty()) {
        apply(() -> store.unregister(byUser(registry)));
      }
      registry.clear();
    });
    worker.shutdown();
    try {
      if (!worker.awaitTermination(2, TimeUnit.SECONDS)) {
        log.warn("Presence shutdown timed out; this backend's sockets expire after the TTL");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    running = false;
  }
}
