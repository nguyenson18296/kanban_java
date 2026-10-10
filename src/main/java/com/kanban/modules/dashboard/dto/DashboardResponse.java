package com.kanban.modules.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * GET /projects/{projectId}/dashboard (JSP-44). Counts every task AND subtask (each its own unit) in the
 * project's non-archived columns. Also the cached Redis payload: changing its shape means bumping the
 * cache key version in {@code DashboardCache}.
 */
public record DashboardResponse(
    @Schema(example = "UrzWUH3e") String project_id,
    @Schema(example = "12", description = "Tasks and subtasks in the project's non-archived columns")
    long total_tasks,
    @Schema(example = "2", description = "due_date before computed_at and status not done/cancelled")
    long overdue_tasks,
    @Schema(description = "Task count per status; every status is present, 0 when empty")
    ByStatus by_status,
    @Schema(description = "Assignees with at least one task, most tasks first. A task with several assignees "
        + "counts once for each, so the sum can exceed total_tasks")
    List<AssigneeCount> by_assignee,
    @Schema(example = "3", description = "Tasks with no assignee") long unassigned_tasks,
    @Schema(example = "2026-10-06T07:00:00.000Z",
        description = "When these numbers were computed (unchanged when served from cache)")
    Instant computed_at) {

  public record ByStatus(
      @Schema(example = "5") long open,
      @Schema(example = "3") long in_progress,
      @Schema(example = "1") long in_review,
      @Schema(example = "2") long done,
      @Schema(example = "1") long cancelled) {}

  public record AssigneeCount(
      @Schema(example = "22222222-2222-4222-8222-222222222222") String user_id,
      @Schema(example = "4") long task_count) {}
}
