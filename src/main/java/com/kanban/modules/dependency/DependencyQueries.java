package com.kanban.modules.dependency;

import com.kanban.modules.dependency.dto.TaskSummaryDto;
import java.util.List;

/** The two things Spring Data cannot express: the recursive graph walk and the advisory lock. */
public interface DependencyQueries {
  /**
   * Serializes dependency writes within one project for the rest of the transaction, so two
   * concurrent adds cannot each pass the cycle check and jointly close a loop.
   */
  void lockProjectDependencies(String projectId);

  /**
   * Of {@code candidateIds}, those reachable from {@code taskId} by walking blocks-edges forward.
   * The walk is seeded at {@code taskId} itself, so a candidate equal to it comes back too — that
   * is the self-reference case. A non-empty result means the edge would close a cycle.
   */
  List<String> findReachableFrom(String taskId, List<String> candidateIds);

  /** Of {@code ids}, those that are tasks in {@code projectId}. Anything missing is masked as a 404. */
  List<TaskSummaryDto> findTasksInProject(List<String> ids, String projectId);
}
