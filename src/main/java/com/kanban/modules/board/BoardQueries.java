package com.kanban.modules.board;

import com.kanban.modules.task.Task;
import java.util.List;
import java.util.Map;

/** The two filtered board queries (count per column + top-N task ids per column). */
public interface BoardQueries {
  record Filters(List<Integer> columnIds, String priority, String search, String assigneeId, Integer labelId) {}

  /** {@code column_id → COUNT(*)} for tasks matching the filters. */
  Map<Integer, Long> countPerColumn(Filters filters);

  /** Top {@code tasksPerColumn} matching tasks per column (ROW_NUMBER), with assignees and labels loaded. */
  List<Task> topTasksPerColumn(Filters filters, int tasksPerColumn);
}
