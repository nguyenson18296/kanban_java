package com.kanban.modules.activity;

import com.kanban.modules.activity.events.TaskActivityAction;
import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class TaskActivityActionConverter extends WireEnumConverter<TaskActivityAction> {
  public TaskActivityActionConverter() {
    super(TaskActivityAction.class);
  }
}
