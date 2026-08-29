package com.kanban.modules.events;

import com.kanban.common.json.Json;
import com.kanban.common.util.Dates;
import com.kanban.modules.notification.events.BaseNotificationEvent;
import com.kanban.modules.notification.events.CommentCreatedEvent;
import com.kanban.modules.notification.events.CommentMentionedEvent;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import com.kanban.modules.notification.events.TaskUpdatedEvent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** Pushes {@code notification:new} to each recipient's room (camelCase WS payload, as in Nest). */
@Service
public class EventsService {
  private static final Logger log = LoggerFactory.getLogger(EventsService.class);
  private final EventsGateway eventsGateway;

  public EventsService(EventsGateway eventsGateway) {
    this.eventsGateway = eventsGateway;
  }

  @Async("eventExecutor")
  @EventListener
  public void handleCommentCreated(CommentCreatedEvent event) {
    emitToRecipients(event, "comment_created");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleCommentMentioned(CommentMentionedEvent event) {
    emitToRecipients(event, "comment_mentioned");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleTaskAssigned(TaskAssignedEvent event) {
    emitToRecipients(event, "task_assigned");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleTaskUpdated(TaskUpdatedEvent event) {
    emitToRecipients(event, "task_updated");
  }

  private void emitToRecipients(BaseNotificationEvent event, String type) {
    Map<String, Object> data = Json.map(
        "type", type,
        "actorId", event.actor_id(),
        "entityType", event.entity_type(),
        "entityId", event.entity_id(),
        "payload", event.payload());
    List<String> filtered = event.recipient_ids().stream().filter(id -> !id.equals(event.actor_id())).toList();
    for (String recipientId : filtered) {
      emitNotification(recipientId, data);
    }
  }

  private void emitNotification(String recipientId, Map<String, Object> data) {
    try {
      Map<String, Object> payload = new LinkedHashMap<>(data);
      payload.put("createdAt", Dates.iso(Instant.now()));
      eventsGateway.emitToUser(recipientId, "notification:new", payload);
    } catch (RuntimeException e) {
      log.error("Failed to emit notification to user {}", recipientId, e);
    }
  }
}
