package com.kanban.modules.task;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class TaskPriorityConverter extends WireEnumConverter<TaskPriority> {
  public TaskPriorityConverter() {
    super(TaskPriority.class);
  }
}
