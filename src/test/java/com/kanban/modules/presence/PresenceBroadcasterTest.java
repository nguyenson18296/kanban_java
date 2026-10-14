package com.kanban.modules.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.modules.events.socket.SocketServer;
import com.kanban.modules.project.ProjectMemberRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PresenceBroadcasterTest {
  private static final Instant AT = Instant.parse("2026-10-14T01:02:03.456Z");

  private ProjectMemberRepository repo;
  private SocketServer server;
  private PresenceBroadcaster broadcaster;

  @BeforeEach
  void setUp() {
    repo = mock(ProjectMemberRepository.class);
    server = mock(SocketServer.class);
    broadcaster = new PresenceBroadcaster(repo, server);
    when(repo.findProjectIdsByUserId("u1")).thenReturn(List.of("p1", "p2"));
  }

  @AfterEach
  void tearDown() {
    broadcaster.stop();
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, Object>> payloads() {
    return ArgumentCaptor.forClass(Map.class);
  }

  @Test
  @DisplayName("sends presence:update with the Redis timestamp to each of the user's project rooms")
  void sendsToProjectRooms() {
    broadcaster.send(new PresenceTransition("u1", true, 2, AT));
    ArgumentCaptor<Map<String, Object>> payload = payloads();
    verify(server).emitToRoom(eq("project:p1"), eq("presence:update"), payload.capture());
    verify(server).emitToRoom(eq("project:p2"), eq("presence:update"), eq(payload.getValue()));
    assertThat(payload.getValue()).containsExactly(entry("userId", "u1"), entry("isOnline", true),
        entry("connectionCount", 2), entry("timestamp", "2026-10-14T01:02:03.456Z"));
  }

  @Test
  @DisplayName("a user without projects gets no event, and a database failure is not thrown")
  void noRooms() {
    when(repo.findProjectIdsByUserId("u2")).thenReturn(List.of());
    broadcaster.send(new PresenceTransition("u2", true, 1, AT));
    when(repo.findProjectIdsByUserId("u3")).thenThrow(new IllegalStateException("db down"));
    assertThatCode(() -> broadcaster.send(new PresenceTransition("u3", true, 1, AT))).doesNotThrowAnyException();
    verifyNoInteractions(server);
  }

  @Test
  @DisplayName("broadcasts asynchronously in transition order")
  void keepsOrder() {
    when(repo.findProjectIdsByUserId("u1")).thenReturn(List.of("p1"));
    broadcaster.broadcast(new PresenceTransition("u1", true, 1, AT));
    broadcaster.broadcast(new PresenceTransition("u1", false, 0, AT.plusSeconds(1)));
    ArgumentCaptor<Map<String, Object>> payload = payloads();
    verify(server, timeout(2000).times(2)).emitToRoom(any(), any(), payload.capture());
    assertThat(payload.getAllValues()).extracting(p -> p.get("isOnline")).containsExactly(true, false);
  }
}
