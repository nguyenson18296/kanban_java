package com.kanban.modules.notification.events;

import java.util.List;
import java.util.Map;

public record TaskAssignedEvent(String actor_id, String entity_id, List<String> recipient_ids,
    Map<String, Object> payload) implements BaseNotificationEvent {
  @Override
  public String entity_type() {
    return "task";
  }
}
