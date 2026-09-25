package com.kanban.modules.activity.events;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum TaskActivityAction implements WireEnum {
  TASK_CREATED("task_created"),
  TASK_TITLE_UPDATED("task_title_updated"),
  TASK_DESCRIPTION_UPDATED("task_description_updated"),
  TASK_STATUS_CHANGED("task_status_changed"),
  TASK_PRIORITY_CHANGED("task_priority_changed"),
  TASK_DUE_DATE_CHANGED("task_due_date_changed"),
  TASK_ASSIGNEE_ADDED("task_assignee_added"),
  TASK_ASSIGNEE_REMOVED("task_assignee_removed"),
  TASK_LABEL_ADDED("task_label_added"),
  TASK_LABEL_REMOVED("task_label_removed"),
  TASK_MOVED("task_moved"),
  TASK_REORDERED("task_reordered"),
  TASK_DEPENDENCY_ADDED("task_dependency_added"),
  TASK_DEPENDENCY_REMOVED("task_dependency_removed");

  private final String value;

  TaskActivityAction(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
