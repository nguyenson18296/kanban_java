package com.kanban.modules.task;

/** The position stored procedures (sql/kanban_tasks.sql §8) — Nest calls them via DataSource.query. */
public interface TaskPositionFunctions {
  void moveTask(String taskId, int columnId, int position);

  void reorderTask(String taskId, int position);

  void reorderSubtask(String subtaskId, String parentId, int position);
}
