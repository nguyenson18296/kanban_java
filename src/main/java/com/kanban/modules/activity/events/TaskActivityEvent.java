package com.kanban.modules.activity.events;

import java.util.Collections;
import java.util.Map;

public record TaskActivityEvent(String actor_id, String task_id, TaskActivityAction action,
    Map<String, Object> payload) {
  public TaskActivityEvent(String actorId, String taskId, TaskActivityAction action) {
    this(actorId, taskId, action, Collections.emptyMap());
  }
}
