package com.kanban.modules.task;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class TaskStatusConverter extends WireEnumConverter<TaskStatus> {
  public TaskStatusConverter() {
    super(TaskStatus.class);
  }
}
