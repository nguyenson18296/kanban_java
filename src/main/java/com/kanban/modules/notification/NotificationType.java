package com.kanban.modules.notification;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum NotificationType implements WireEnum {
  COMMENT_CREATED("comment_created"),
  COMMENT_MENTIONED("comment_mentioned"),
  TASK_ASSIGNED("task_assigned"),
  TASK_UPDATED("task_updated");

  private final String value;

  NotificationType(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
