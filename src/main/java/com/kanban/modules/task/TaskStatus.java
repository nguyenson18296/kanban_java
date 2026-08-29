package com.kanban.modules.task;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum TaskStatus implements WireEnum {
  OPEN("open"),
  IN_PROGRESS("in_progress"),
  IN_REVIEW("in_review"),
  DONE("done"),
  CANCELLED("cancelled");

  private final String value;

  TaskStatus(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
