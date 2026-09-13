package com.kanban.modules.board;

import com.kanban.modules.task.Task;
import java.util.List;
import java.util.Map;

/**
 * The two filtered board queries (count per column + top-N task ids per column). {@code search} is
 * raw websearch text matched against {@code tasks.search_vector} via {@code TaskSearchSql} (JAV-34).
 */
public interface BoardQueries {
  /** {@code search} null = no text filter (the service maps blank input to null). */
  record Filters(List<Integer> columnIds, String priority, String search, String assigneeId, Integer labelId) {}

  /** {@code column_id → COUNT(*)} for tasks matching the filters. */
  Map<Integer, Long> countPerColumn(Filters filters);

  /** Top {@code tasksPerColumn} matching tasks per column (ROW_NUMBER), with assignees and labels loaded. */
  List<Task> topTasksPerColumn(Filters filters, int tasksPerColumn);
}
