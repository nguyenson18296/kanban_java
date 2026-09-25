package com.kanban.modules.dependency;

import com.kanban.common.validation.WireEnum;
import com.kanban.modules.dependency.dto.TaskSummaryDto;
import com.kanban.modules.task.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JpaDependencyQueries implements DependencyQueries {
  @PersistenceContext
  private EntityManager em;

  @Override
  public void lockProjectDependencies(String projectId) {
    em.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:projectId))")
        .setParameter("projectId", projectId)
        .getSingleResult();
  }

  @Override
  public List<String> findReachableFrom(String taskId, List<String> candidateIds) {
    if (candidateIds.isEmpty()) {
      return List.of();
    }
    // UNION, not UNION ALL: deduplication makes the walk terminate even if a cycle ever
    // reached the table. The seed row is taskId itself, so a candidate equal to it comes
    // back too -- that is the self-reference case.
    List<?> rows = em.createNativeQuery(
        "WITH RECURSIVE downstream(id) AS ("
            + "  SELECT CAST(:taskId AS uuid)"
            + "  UNION"
            + "  SELECT d.blocked_task_id FROM task_dependencies d"
            + "  JOIN downstream s ON d.blocking_task_id = s.id"
            + ") SELECT id FROM downstream WHERE id IN (:candidateIds)")
        .setParameter("taskId", taskId)
        .setParameter("candidateIds", candidateIds)
        .getResultList();
    List<String> out = new ArrayList<>();
    for (Object row : rows) {
      out.add(String.valueOf(row));
    }
    return out;
  }

  @Override
  public List<TaskSummaryDto> findTasksInProject(List<String> ids, String projectId) {
    if (ids.isEmpty()) {
      return List.of();
    }
    List<?> rows = em.createNativeQuery(
        "SELECT t.id, t.ticket_id, t.title, t.status, t.column_id FROM tasks t "
            + "JOIN kanban_columns c ON c.id = t.column_id "
            + "WHERE t.id IN (:ids) AND c.project_id = :projectId")
        .setParameter("ids", ids)
        .setParameter("projectId", projectId)
        .getResultList();
    List<TaskSummaryDto> out = new ArrayList<>();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      out.add(new TaskSummaryDto(
          String.valueOf(cols[0]),
          cols[1] == null ? null : String.valueOf(cols[1]),
          cols[2] == null ? null : String.valueOf(cols[2]),
          statusOf(cols[3]),
          cols[4] == null ? null : ((Number) cols[4]).intValue()));
    }
    return out;
  }

  /** Same contract as {@code WireEnumConverter}: an unknown value is schema drift — fail loudly. */
  static TaskStatus statusOf(Object raw) {
    if (raw == null) {
      return null;
    }
    TaskStatus status = WireEnum.fromValue(TaskStatus.class, String.valueOf(raw));
    if (status == null) {
      throw new IllegalArgumentException("Unknown TaskStatus value: " + raw);
    }
    return status;
  }
}
