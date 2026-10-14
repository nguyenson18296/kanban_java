package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.modules.events.socket.SocketServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class PresenceTrackerTest {
  private static final Instant AT = Instant.parse("2026-10-14T01:02:03.456Z");
  private static final ServiceUnavailableException DOWN =
      new ServiceUnavailableException("Presence temporarily unavailable. Please try again later.");

  private RedisPresenceStore store;
  private PresenceBroadcaster broadcaster;
  private SocketServer server;
  private PresenceTracker tracker;

  @BeforeEach
  void setUp() {
    store = mock(RedisPresenceStore.class);
    broadcaster = mock(PresenceBroadcaster.class);
    server = mock(SocketServer.class);
    // TTL 30s / interval 10s: reconcile from the 4th consecutive healthy heartbeat.
    tracker = new PresenceTracker(store, broadcaster, server,
        new PresenceProperties(Duration.ofSeconds(10), Duration.ofSeconds(30), "p"));
    when(server.isConnected(any())).thenReturn(true);
    when(store.register(any())).thenReturn(List.of());
    when(store.unregister(any())).thenReturn(List.of());
    when(store.due(anyInt())).thenReturn(List.of());
  }

  @SuppressWarnings("unchecked")
  private Map<String, List<String>> lastRegistered() {
    ArgumentCaptor<Map<String, List<String>>> captor = ArgumentCaptor.forClass(Map.class);
    verify(store, atLeastOnce()).register(captor.capture());
    return captor.getValue();
  }

  private void ticks(int n) {
    for (int i = 0; i < n; i++) {
      tracker.tick();
    }
  }

  @Test
  @DisplayName("broadcasts exactly the transitions the store reports")
  void broadcastsStoreTransitions() {
    PresenceTransition online = new PresenceTransition("u1", true, 1, AT);
    when(store.register(Map.of("u1", List.of("s1")))).thenReturn(List.of(online));
    tracker.handleConnected("u1", "s1");
    tracker.handleConnected("u1", "s2");
    verify(store).register(Map.of("u1", List.of("s2")));
    verify(broadcaster).broadcast(online);

    PresenceTransition offline = new PresenceTransition("u1", false, 0, AT);
    when(store.unregister(Map.of("u1", List.of("s2")))).thenReturn(List.of(offline));
    tracker.handleDisconnected("u1", "s1");
    tracker.handleDisconnected("u1", "s2");
    verify(broadcaster).broadcast(offline);
    verify(broadcaster, times(2)).broadcast(any());
  }

  @Test
  @DisplayName("ignores a connect whose socket is already gone")
  void skipsClosedSocket() {
    when(server.isConnected("s1")).thenReturn(false);
    tracker.handleConnected("u1", "s1");
    verifyNoInteractions(store);
    tracker.tick();
    assertThat(lastRegistered()).isEmpty();
  }

  @Test
  @DisplayName("unregisters under the user the socket was registered with, else the event's user")
  void unregistersOwner() {
    tracker.handleConnected("u1", "s1");
    tracker.handleDisconnected("u2", "s1");
    verify(store).unregister(Map.of("u1", List.of("s1")));
    tracker.handleDisconnected("u3", "s9");
    verify(store).unregister(Map.of("u3", List.of("s9")));
  }

  @Test
  @DisplayName("heartbeat refreshes every local socket, grouped by user")
  void heartbeatGroupsByUser() {
    tracker.handleConnected("u1", "s1");
    tracker.handleConnected("u1", "s2");
    tracker.handleConnected("u2", "s3");
    tracker.tick();
    Map<String, List<String>> registered = lastRegistered();
    assertThat(registered).containsOnlyKeys("u1", "u2");
    assertThat(registered.get("u1")).containsExactlyInAnyOrder("s1", "s2");
    assertThat(registered.get("u2")).containsExactly("s3");
  }

  @Test
  @DisplayName("heartbeat first unregisters sockets whose disconnect never arrived")
  void prunesClosedSockets() {
    tracker.handleConnected("u1", "s1");
    tracker.handleConnected("u1", "s2");
    when(server.isConnected("s2")).thenReturn(false);
    clearInvocations(store);
    tracker.tick();
    InOrder order = inOrder(store);
    order.verify(store).unregister(Map.of("u1", List.of("s2")));
    order.verify(store).register(Map.of("u1", List.of("s1")));
  }

  @Test
  @DisplayName("a Redis failure broadcasts nothing, keeps the sockets and re-registers them all on recovery")
  void survivesOutage() {
    tracker.handleConnected("u1", "s1");
    when(store.register(any())).thenThrow(DOWN);
    tracker.tick();

    tracker.handleConnected("u2", "s2");
    tracker.handleDisconnected("u1", "gone");
    tracker.tick();
    verify(store, never()).unregister(any());
    verify(store, never()).register(Map.of("u2", List.of("s2")));

    PresenceTransition online = new PresenceTransition("u2", true, 1, AT);
    doReturn(List.of(online)).when(store).register(any());
    tracker.tick();
    assertThat(lastRegistered()).isEqualTo(Map.of("u1", List.of("s1"), "u2", List.of("s2")));
    verify(broadcaster).broadcast(online);
    verify(broadcaster, times(1)).broadcast(any());
  }

  @Test
  @DisplayName("reconciles expired sockets only after a TTL of healthy heartbeats")
  void reconcileWaitsForTtl() {
    ticks(3);
    verify(store, never()).due(anyInt());

    PresenceTransition offline = new PresenceTransition("u9", false, 0, AT);
    when(store.due(anyInt())).thenReturn(List.of("u9"));
    when(store.unregister(Map.of("u9", List.of()))).thenReturn(List.of(offline));
    tracker.tick();
    verify(broadcaster).broadcast(offline);
  }

  @Test
  @DisplayName("a Redis failure restarts the wait before reconciling")
  void failureRestartsReconcileWait() {
    ticks(4);
    verify(store).due(anyInt());
    when(store.register(any())).thenThrow(DOWN).thenReturn(List.of());
    tracker.tick();
    ticks(3);
    verify(store, times(1)).due(anyInt());
    tracker.tick();
    verify(store, times(2)).due(anyInt());
  }

  @Test
  @DisplayName("reconcile keeps reading while full batches are due")
  void reconcileDrainsBatches() {
    List<String> full = new ArrayList<>(Collections.nCopies(PresenceTracker.RECONCILE_BATCH, "u"));
    when(store.due(anyInt())).thenReturn(full, List.of("last"), List.of());
    ticks(4);
    verify(store, times(2)).due(PresenceTracker.RECONCILE_BATCH);
    verify(store).unregister(Map.of("last", List.of()));
  }

  @Test
  @DisplayName("graceful shutdown unregisters every local socket without broadcasting")
  void shutdown() {
    tracker.handleConnected("u1", "s1");
    tracker.handleConnected("u2", "s2");
    when(store.unregister(any())).thenReturn(List.of(new PresenceTransition("u1", false, 0, AT)));
    tracker.stop();
    verify(store).unregister(Map.of("u1", List.of("s1"), "u2", List.of("s2")));
    verifyNoInteractions(broadcaster);

    tracker.connected("u3", "s3");
    tracker.disconnected("u1", "s1");
    verify(store, never()).register(Map.of("u3", List.of("s3")));
  }
}
