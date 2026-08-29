package com.kanban.modules.task;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum TaskPriority implements WireEnum {
  NO_PRIORITY("no_priority"),
  URGENT("urgent"),
  HIGH("high"),
  MEDIUM("medium"),
  LOW("low");

  private final String value;

  TaskPriority(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
