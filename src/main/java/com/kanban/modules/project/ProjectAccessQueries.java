package com.kanban.modules.project;

import java.util.Optional;

/** The raw lookups ProjectAccessService runs through the DataSource query builder in Nest. */
public interface ProjectAccessQueries {
  /** {@code SELECT col.project_id FROM tasks task JOIN kanban_columns col ON col.id = task.column_id WHERE task.id = ?}. */
  Optional<String> findProjectIdForTask(String taskId);

  /** {@code SELECT col.project_id FROM kanban_columns col WHERE col.id = ?}. */
  Optional<String> findProjectIdForColumn(int columnId);
}
