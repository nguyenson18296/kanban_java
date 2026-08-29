package com.kanban.modules.board.dto;

import com.kanban.modules.task.TaskPriority;
import com.kanban.modules.task.TaskStatus;
import java.time.Instant;
import java.util.List;

public record BoardResponse(List<BoardColumn> columns) {
  public record BoardAssignee(String id, String full_name, String avatar_url) {}

  public record BoardLabel(Integer id, String name, String color) {}

  public record BoardTask(String id, String title, String ticket_id, int position, TaskStatus status,
      TaskPriority priority, Instant created_at, Instant due_date, List<BoardAssignee> assignees,
      List<BoardLabel> labels) {}

  public record BoardColumn(Integer id, String name, int position, String color, long task_count,
      List<BoardTask> tasks) {}
}
