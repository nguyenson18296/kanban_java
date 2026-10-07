package com.kanban.modules.dashboard;

import com.kanban.modules.task.TaskStatus;
import java.time.Instant;
import java.util.List;

/** The project dashboard aggregate (JSP-44), which Spring Data cannot express. */
public interface DashboardQueries {
  /** Tasks with this status; of those, how many are overdue and how many have no assignee. */
  record StatusRow(TaskStatus status, long tasks, long overdue, long unassigned) {}

  /** Tasks assigned to this user (a task with several assignees counts once for each of them). */
  record AssigneeRow(String userId, long tasks) {}

  /** Only statuses and assignees with at least one task appear. */
  record Snapshot(List<StatusRow> statuses, List<AssigneeRow> assignees) {}

  /**
   * Counts every task and subtask in the project's non-archived columns, in ONE statement so every
   * number comes from the same database snapshot. A task is overdue when its due_date is before
   * {@code asOf} and it is neither done nor cancelled.
   */
  Snapshot snapshot(String projectId, Instant asOf);
}
