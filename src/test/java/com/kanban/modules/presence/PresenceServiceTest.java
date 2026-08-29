package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.modules.events.EventsGateway;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.presence.dto.PresenceStateDto;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.project.ProjectMemberRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/** Port of presence.service.spec.ts */
class PresenceServiceTest {
  private ProjectMemberRepository repo;
  private SocketServer server;
  private PresenceService service;

  private static WsConnectionOpenedEvent opened(String user, String socket) {
    return new WsConnectionOpenedEvent(user, socket);
  }

  private static WsConnectionClosedEvent closed(String user, String socket) {
    return new WsConnectionClosedEvent(user, socket);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, Object>> payloadCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }

  @BeforeEach
  void setUp() {
    repo = mock(ProjectMemberRepository.class);
    server = mock(SocketServer.class);
    EventsGateway gateway = mock(EventsGateway.class);
    when(gateway.getServer()).thenReturn(server);
    service = new PresenceService(gateway, repo);
    when(repo.findProjectIdsByUserId(any())).thenReturn(List.of());
  }

  @Nested
  class HandleConnectionOpened {
    @Test
    @DisplayName("records the socket as connected for the user")
    void records() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      assertThat(service.isOnline("user-1")).isTrue();
      assertThat(service.getConnectionCount("user-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("increments connection count for additional sockets")
    void increments() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      assertThat(service.getConnectionCount("user-1")).isEqualTo(2);
    }
  }

  @Nested
  class HandleConnectionClosed {
    @Test
    @DisplayName("decrements connection count")
    void decrements() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      assertThat(service.isOnline("user-1")).isTrue();
      assertThat(service.getConnectionCount("user-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("marks user offline when last socket closes")
    void offline() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      assertThat(service.isOnline("user-1")).isFalse();
      assertThat(service.getConnectionCount("user-1")).isZero();
    }

    @Test
    @DisplayName("is a no-op when closing an unknown socket")
    void unknownSocket() {
      assertThatCode(() -> service.handleConnectionClosed(closed("user-1", "never-connected")))
          .doesNotThrowAnyException();
      assertThat(service.isOnline("user-1")).isFalse();
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
  class PresenceBroadcasts {
    @Test
    @DisplayName("broadcasts isOnline=true to each project room on first connect")
    void broadcastsOnline() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1", "p2"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      ArgumentCaptor<Map<String, Object>> captor = payloadCaptor();
      verify(server, times(2)).emitToRoom(any(), eq("presence:update"), captor.capture());
      verify(server).emitToRoom(eq("project:p1"), eq("presence:update"), any());
      verify(server).emitToRoom(eq("project:p2"), eq("presence:update"), any());
      assertThat(captor.getAllValues()).allSatisfy(payload -> {
        assertThat(payload).containsEntry("userId", "user-1").containsEntry("isOnline", true)
            .containsEntry("connectionCount", 1);
        assertThat(payload.get("timestamp")).isInstanceOf(String.class);
      });
    }

    @Test
    @DisplayName("does not broadcast on second connect for the same user")
    void noBroadcastOnSecondConnect() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      clearInvocations(server);
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      verify(server, never()).emitToRoom(any(), any(), any());
    }

    @Test
    @DisplayName("does not broadcast when the user has no project memberships")
    void noBroadcastWithoutMemberships() {
      when(repo.findProjectIdsByUserId("lonely-user")).thenReturn(List.of());
      service.handleConnectionOpened(opened("lonely-user", "socket-1"));
      verify(server, never()).emitToRoom(any(), any(), any());
    }

    @Test
    @DisplayName("broadcasts isOnline=false to project rooms when the last socket closes")
    void broadcastsOffline() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      clearInvocations(server);
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      ArgumentCaptor<Map<String, Object>> captor = payloadCaptor();
      verify(server).emitToRoom(eq("project:p1"), eq("presence:update"), captor.capture());
      assertThat(captor.getValue()).containsEntry("userId", "user-1").containsEntry("isOnline", false)
          .containsEntry("connectionCount", 0);
    }

    @Test
    @DisplayName("does not broadcast offline when a non-last socket closes")
    void noOfflineForNonLast() {
      when(repo.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1"));
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionOpened(opened("user-1", "socket-2"));
      clearInvocations(server);
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      verify(server, never()).emitToRoom(any(), any(), any());
    }
  }

  @Nested
  class GetOnlineStates {
    @Test
    @DisplayName("returns isOnline=true with connectionCount and lastChangedAt for online users")
    void online() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      List<PresenceStateDto> result = service.getOnlineStates(List.of("user-1"));
      assertThat(result).hasSize(1);
      assertThat(result.get(0).userId()).isEqualTo("user-1");
      assertThat(result.get(0).isOnline()).isTrue();
      assertThat(result.get(0).connectionCount()).isEqualTo(1);
      assertThat(result.get(0).lastChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("returns isOnline=false and null lastChangedAt for never-seen users")
    void neverSeen() {
      assertThat(service.getOnlineStates(List.of("ghost-user")))
          .containsExactly(new PresenceStateDto("ghost-user", false, 0, null));
    }

    @Test
    @DisplayName("returns lastChangedAt for users who went offline")
    void wentOffline() {
      service.handleConnectionOpened(opened("user-1", "socket-1"));
      service.handleConnectionClosed(closed("user-1", "socket-1"));
      PresenceStateDto state = service.getOnlineStates(List.of("user-1")).get(0);
      assertThat(state.userId()).isEqualTo("user-1");
      assertThat(state.isOnline()).isFalse();
      assertThat(state.connectionCount()).isZero();
      assertThat(state.lastChangedAt()).isNotNull();
    }

    @Test
    @DisplayName("preserves input order and returns one entry per userId")
    void preservesOrder() {
      assertThat(service.getOnlineStates(List.of("a", "b", "c")).stream().map(PresenceStateDto::userId))
          .containsExactly("a", "b", "c");
    }
  }
}
