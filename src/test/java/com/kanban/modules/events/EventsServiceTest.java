package com.kanban.modules.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.kanban.common.json.Json;
import com.kanban.modules.notification.events.CommentCreatedEvent;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Port of events.gateway.spec.ts — describe('EventsService') */
class EventsServiceTest {
  private EventsGateway gateway;
  private EventsService service;

  private static Map<String, Object> commentPayload() {
    return Json.map(
        "task_id", "task-1",
        "task_title", "Test Task",
        "ticket_id", "KAN-1",
        "comment_id", "comment-1",
        "comment_preview", "Hello...",
        "author", Json.map("id", "actor-1", "full_name", "Actor", "avatar_url", null));
  }

  @BeforeEach
  void setUp() {
    gateway = mock(EventsGateway.class);
    service = new EventsService(gateway);
  }

  @Nested
  class HandleCommentCreated {
    @Test
    @DisplayName("should emit notification to recipient")
    void emits() {
      service.handleCommentCreated(new CommentCreatedEvent("actor-1", "entity-1", List.of("recipient-1"),
          commentPayload()));
      @SuppressWarnings("unchecked")
      ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
      verify(gateway).emitToUser(eq("recipient-1"), eq("notification:new"), captor.capture());
      assertThat(captor.getValue())
          .containsEntry("type", "comment_created")
          .containsEntry("actorId", "actor-1")
          .containsKey("createdAt");
    }

    @Test
    @DisplayName("should skip self-notification")
    void skipsSelf() {
      service.handleCommentCreated(new CommentCreatedEvent("actor-1", "entity-1", List.of("actor-1"),
          commentPayload()));
      verify(gateway, never()).emitToUser(any(), any(), any());
    }
  }

  @Test
  @DisplayName("handleTaskAssigned should emit to all recipients except actor")
  void taskAssigned() {
    service.handleTaskAssigned(new TaskAssignedEvent("actor-1", "entity-1",
        List.of("recipient-1", "recipient-2", "actor-1"),
        Json.map("task_id", "task-1", "task_title", "Test Task", "ticket_id", "KAN-1")));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(gateway, times(2)).emitToUser(any(), eq("notification:new"), captor.capture());
    verify(gateway).emitToUser(eq("recipient-1"), eq("notification:new"), any());
    verify(gateway).emitToUser(eq("recipient-2"), eq("notification:new"), any());
    assertThat(captor.getAllValues()).allSatisfy(m -> assertThat(m).containsEntry("type", "task_assigned"));
  }
}
