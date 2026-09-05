package com.kanban.modules.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.json.Json;
import com.kanban.modules.events.guards.WsJwtGuard;
import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.presence.events.WsConnectionClosedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import com.kanban.testing.FakeSocketClient;
import com.kanban.testing.RecordingEventBus;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of events.gateway.spec.ts — describe('EventsGateway') */
class EventsGatewayTest {
  private final User mockUser = new User("user-1", "test@example.com", "Test", UserRole.BACKEND_DEVELOPER, null, true);
  private WsJwtGuard wsJwtGuard;
  private RecordingEventBus events;
  private SocketServer server;
  private EventsGateway gateway;

  private static Map<String, Object> auth(String token) {
    Map<String, Object> m = new HashMap<>();
    m.put("token", token);
    return m;
  }

  @BeforeEach
  void setUp() {
    wsJwtGuard = mock(WsJwtGuard.class);
    events = new RecordingEventBus();
    server = mock(SocketServer.class);
    gateway = new EventsGateway(wsJwtGuard, events);
    gateway.setServer(server);
  }

  @Nested
  class HandleConnection {
    @Test
    @DisplayName("should authenticate and join user room on valid token")
    void validToken() {
      FakeSocketClient client = new FakeSocketClient("socket-1", auth("valid-token"));
      when(wsJwtGuard.validateToken(client)).thenReturn(mockUser);

      gateway.handleConnection(client);

      verify(wsJwtGuard).validateToken(client);
      assertThat(client.data().get("user")).isSameAs(mockUser);
      assertThat(client.joined).containsExactly("user:user-1");
      assertThat(client.emittedEvent("connection:established", Json.map("userId", "user-1"))).isTrue();
    }

    @Test
    @DisplayName("should disconnect client on invalid token")
    void invalidToken() {
      FakeSocketClient client = new FakeSocketClient("socket-2", auth("bad-token"));
      when(wsJwtGuard.validateToken(client)).thenThrow(new RuntimeException("Invalid"));

      gateway.handleConnection(client);

      assertThat(client.emittedEvent("connection:error", Json.map("message", "Authentication failed"))).isTrue();
      assertThat(client.disconnects).containsExactly(true);
      assertThat(client.joined).isEmpty();
    }

    @Test
    @DisplayName("should emit ws.connection.opened after successful auth")
    void emitsOpened() {
      FakeSocketClient client = new FakeSocketClient("socket-1", auth("valid-token"));
      when(wsJwtGuard.validateToken(client)).thenReturn(mockUser);

      gateway.handleConnection(client);

      assertThat(events.emittedOf(WsConnectionOpenedEvent.class))
          .containsExactly(new WsConnectionOpenedEvent("user-1", "socket-1"));
    }

    @Test
    @DisplayName("should NOT emit ws.connection.opened on auth failure")
    void noOpenedOnFailure() {
      FakeSocketClient client = new FakeSocketClient("socket-2", auth("bad-token"));
      when(wsJwtGuard.validateToken(client)).thenThrow(new RuntimeException("Invalid"));

      gateway.handleConnection(client);

      assertThat(events.emittedOf(WsConnectionOpenedEvent.class)).isEmpty();
    }
  }

  @Nested
  class HandleDisconnect {
    @Test
    @DisplayName("should log disconnect with user id")
    void withUser() {
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      client.data().put("user", mockUser);
      assertThatCode(() -> gateway.handleDisconnect(client)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should handle disconnect without user data")
    void withoutUser() {
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      assertThatCode(() -> gateway.handleDisconnect(client)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should emit ws.connection.closed when user is known")
    void emitsClosed() {
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      client.data().put("user", mockUser);
      gateway.handleDisconnect(client);
      assertThat(events.emittedOf(WsConnectionClosedEvent.class))
          .containsExactly(new WsConnectionClosedEvent("user-1", "socket-1"));
    }

    @Test
    @DisplayName("should NOT emit ws.connection.closed when user is unknown")
    void noClosedWhenUnknown() {
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      gateway.handleDisconnect(client);
      assertThat(events.emittedOf(WsConnectionClosedEvent.class)).isEmpty();
    }
  }

  @Nested
  class HandleTokenRefresh {
    @Test
    @DisplayName("should validate new token, update client.data.user, and re-join room")
    void refreshes() {
      User refreshedUser = new User("user-1", "test@example.com", "Test", UserRole.BACKEND_DEVELOPER, null, true);
      FakeSocketClient client = new FakeSocketClient("socket-1", auth("old-token"));
      client.data().put("user", mockUser);
      when(wsJwtGuard.validateToken(client)).thenReturn(refreshedUser);

      gateway.handleTokenRefresh(client, Json.map("token", "new-token"));

      assertThat(client.handshake().auth().get("token")).isEqualTo("new-token");
      verify(wsJwtGuard).validateToken(client);
      assertThat(client.data().get("user")).isSameAs(refreshedUser);
      assertThat(client.joined).containsExactly("user:user-1");
      assertThat(client.emittedEvent("token:refresh:success", Json.map())).isTrue();
      assertThat(client.disconnects).isEmpty();
    }

    @Test
    @DisplayName("should handle missing handshake.auth gracefully")
    void missingAuth() {
      User refreshedUser = new User("user-1", "test@example.com", "Test", UserRole.BACKEND_DEVELOPER, null, true);
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      client.data().put("user", mockUser);
      when(wsJwtGuard.validateToken(client)).thenReturn(refreshedUser);

      gateway.handleTokenRefresh(client, Json.map("token", "new-token"));

      assertThat(client.handshake().auth()).isEqualTo(Json.map("token", "new-token"));
      assertThat(client.data().get("user")).isSameAs(refreshedUser);
      assertThat(client.emittedEvent("token:refresh:success", Json.map())).isTrue();
    }

    @Test
    @DisplayName("should disconnect client on expired/invalid token")
    void expired() {
      FakeSocketClient client = new FakeSocketClient("socket-1", auth("old-token"));
      client.data().put("user", mockUser);
      when(wsJwtGuard.validateToken(client)).thenThrow(new RuntimeException("Token expired"));

      gateway.handleTokenRefresh(client, Json.map("token", "expired-token"));

      assertThat(client.handshake().auth().get("token")).isEqualTo("expired-token");
      assertThat(client.emittedEvent("token:refresh:error", Json.map("message", "Token refresh failed"))).isTrue();
      assertThat(client.disconnects).containsExactly(true);
      assertThat(client.joined).isEmpty();
    }
  }

  @Test
  @DisplayName("emitToUser should emit event to user room")
  void emitToUser() {
    Map<String, Object> data = Json.map("type", "test", "payload", Json.map());
    gateway.emitToUser("user-1", "notification:new", data);
    verify(server).emitToRoom("user:user-1", "notification:new", data);
  }
}
