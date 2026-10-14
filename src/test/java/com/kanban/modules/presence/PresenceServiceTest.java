package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ServiceUnavailableException;
import com.kanban.modules.events.EventsGateway;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.presence.dto.PresenceStateDto;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectMemberRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Port of presence.service.spec.ts; presence state and broadcasts moved to PresenceTracker (JSP-42). */
class PresenceServiceTest {
  private ProjectMemberRepository repo;
  private SocketServer server;
  private RedisPresenceStore store;
  private PresenceTracker tracker;
  private PresenceService service;

  private static WsConnectionOpenedEvent opened(String user, String socket) {
    return new WsConnectionOpenedEvent(user, socket);
  }

  private static WsConnectionClosedEvent closed(String user, String socket) {
    return new WsConnectionClosedEvent(user, socket);
  }

  @BeforeEach
  void setUp() {
    repo = mock(ProjectMemberRepository.class);
    server = mock(SocketServer.class);
    EventsGateway gateway = mock(EventsGateway.class);
    when(gateway.getServer()).thenReturn(server);
    store = mock(RedisPresenceStore.class);
    tracker = mock(PresenceTracker.class);
    service = new PresenceService(gateway, repo, store, tracker);
    when(repo.findProjectIdsByUserId(any())).thenReturn(List.of());
  }

  @Nested
  class TrackerHandOff {
    @Test
    @DisplayName("hands the connect to the tracker after the room join")
    void connectAfterJoin() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      InOrder order = Mockito.inOrder(server, tracker);
      order.verify(server).joinRooms("socket-1", List.of("project:p1"));
      order.verify(tracker).connected("user-1", "socket-1");
    }

    @Test
    @DisplayName("hands the connect to the tracker even when the project lookup fails")
    void connectDespiteLookupFailure() {
      when(repo.findProjectIdsByUserId("user-1")).thenThrow(new IllegalStateException("db down"));
      assertThatThrownBy(() -> service.handleConnectionOpened(opened("user-1", "socket-1")))
          .isInstanceOf(IllegalStateException.class);
      verify(tracker).connected("user-1", "socket-1");
    }

    @Test
    @DisplayName("hands every disconnect to the tracker, known socket or not")
    void disconnects() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "never-connected"));
      verify(tracker).disconnected("user-1", "socket-1");
      verify(tracker).disconnected("user-1", "never-connected");
    }

    @Test
    @DisplayName("never broadcasts by itself")
    void noBroadcast() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      verify(server, never()).emitToRoom(any(), any(), any());
    }
  }

  @Nested
  class ProjectRoomSubscription {
    @Test
    @DisplayName("joins the new socket to all project rooms on first connect")
    void joinsAll() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1", "p2"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      verify(repo).findProjectIdsByUserId("user-1");
      verify(server).joinRooms("socket-1", List.of("project:p1", "project:p2"));
    }

    @Test
    @DisplayName("joins additional sockets to the same cached project rooms")
    void cached() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      // Repo queried only once — second connect uses the cached set
      verify(repo, times(1)).findProjectIdsByUserId(any());
      verify(server).joinRooms("socket-2", List.of("project:p1"));
    }

    @Test
    @DisplayName("skips socketsJoin when the user has no project memberships")
    void skipsWithoutMemberships() {
      when(repo.findProjectIdsByUserId("lonely-user")).thenReturn(List.of());
      service.handleConnectionOpened(opened("lonely-user", "socket-1"));
      verify(server, never()).joinRooms(any(), anyList());
    }

    @Test
    @DisplayName("queues additional sockets until the first connect's project query resolves")
    void queuesUntilResolved() throws Exception {
      CountDownLatch queryStarted = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      when(repo.findProjectIdsByUserId("user-1")).thenAnswer(inv -> {
        queryStarted.countDown();
        release.await(5, TimeUnit.SECONDS);
        return List.of("p1");
      });
      Thread first = new Thread(() -> service.handleConnectionOpened(opened("user-1", "socket-1")));
      first.start();
      assertThat(queryStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Thread second = new Thread(() -> service.handleConnectionOpened(opened("user-1", "socket-2")));
      second.start();
      // give the second connect time to observe the pending query
      Thread.sleep(100);
      release.countDown();
      first.join(5000);
      second.join(5000);

      // Both sockets must be joined to the project room — not just the first.
      verify(server).joinRooms("socket-1", List.of("project:p1"));
      verify(server).joinRooms("socket-2", List.of("project:p1"));
      verify(server, times(2)).joinRooms(any(), anyList());
      // The DB should still be queried only once — the second event awaits the cached promise.
      verify(repo, times(1)).findProjectIdsByUserId(any());
    }

    @Test
    @DisplayName("drops the project cache after the last socket disconnects")
    void dropsCache() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"), List.of("p2"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      // Second connect should re-query because cache was cleared
      verify(repo, times(2)).findProjectIdsByUserId(any());
      InOrder inOrder = Mockito.inOrder(server);
      inOrder.verify(server).joinRooms("socket-1", List.of("project:p1"));
      inOrder.verify(server).joinRooms("socket-2", List.of("project:p2"));
    }
  }

  @Nested
  class GetOnlineStates {
    @Test
    @DisplayName("returns the shared state read from the store")
    void readsStore() {
      List<PresenceStateDto> states = List.of(
          new PresenceStateDto("user-1", true, 2, "2026-10-12T00:00:00.000Z"),
          new PresenceStateDto("ghost-user", false, 0, null));
      when(store.read(List.of("user-1", "ghost-user"))).thenReturn(states);
      assertThat(service.getOnlineStates(List.of("user-1", "ghost-user"))).isEqualTo(states);
    }

    @Test
    @DisplayName("propagates the generic 503 when Redis is unavailable")
    void redisDown() {
      when(store.read(any())).thenThrow(
          new ServiceUnavailableException("Presence temporarily unavailable. Please try again later."));
      assertThatThrownBy(() -> service.getOnlineStates(List.of("user-1")))
          .isInstanceOf(ServiceUnavailableException.class);
    }
  }
}
