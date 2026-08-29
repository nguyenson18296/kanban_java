package com.kanban.modules.notification.events;

import java.util.List;
import java.util.Map;

/** Common shape of the four notification events (snake_case accessors mirror the TS classes). */
public interface BaseNotificationEvent {
  String actor_id();

  String entity_type();

  String entity_id();

  List<String> recipient_ids();

  Map<String, Object> payload();
}
