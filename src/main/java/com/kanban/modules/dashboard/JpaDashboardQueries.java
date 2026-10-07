package com.kanban.modules.dashboard;

import com.kanban.common.validation.WireEnum;
import com.kanban.modules.task.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class JpaDashboardQueries implements DashboardQueries {
  /**
   * scoped = the counted set: tasks and subtasks in the project's non-archived columns (tasks have no
   * project_id, so the column decides). The status branch never joins task_assignees, so a task with
   * several assignees cannot inflate the totals; the assignee branch counts one row per (task, user) --
   * the table's primary key. One statement = one snapshot, so the two branches always agree.
   */
  static final String SQL = "WITH scoped AS ("
      + "  SELECT t.id, t.status, t.due_date"
      + "  FROM tasks t"
      + "  JOIN kanban_columns c ON c.id = t.column_id"
      + "  WHERE c.project_id = :projectId AND c.is_archived = false"
      + "), status_counts AS ("
      + "  SELECT CAST(s.status AS text) AS k, COUNT(*) AS n,"
      + "    COUNT(*) FILTER (WHERE s.due_date < CAST(:asOf AS timestamptz)"
      + "      AND s.status NOT IN ('done', 'cancelled')) AS overdue,"
      + "    COUNT(*) FILTER (WHERE NOT EXISTS ("
      + "      SELECT 1 FROM task_assignees ta WHERE ta.task_id = s.id)) AS unassigned"
      + "  FROM scoped s GROUP BY s.status"
      + "), assignee_counts AS ("
      + "  SELECT CAST(ta.user_id AS text) AS k, COUNT(*) AS n"
      + "  FROM scoped s JOIN task_assignees ta ON ta.task_id = s.id"
      + "  GROUP BY ta.user_id"
      + ")"
      + " SELECT 'status' AS kind, k, n, overdue, unassigned FROM status_counts"
      + " UNION ALL"
      + " SELECT 'assignee', k, n, 0, 0 FROM assignee_counts";

  @PersistenceContext
  private EntityManager em;

  @Override
  public Snapshot snapshot(String projectId, Instant asOf) {
    List<?> rows = em.createNativeQuery(SQL)
        .setParameter("projectId", projectId)
        .setParameter("asOf", asOf)
        .getResultList();
    List<StatusRow> statuses = new ArrayList<>();
    List<AssigneeRow> assignees = new ArrayList<>();
    for (Object row : rows) {
      Object[] cols = (Object[]) row;
      long tasks = ((Number) cols[2]).longValue();
      if ("status".equals(cols[0])) {
        statuses.add(new StatusRow(statusOf(cols[1]), tasks,
            ((Number) cols[3]).longValue(), ((Number) cols[4]).longValue()));
      } else {
        assignees.add(new AssigneeRow(String.valueOf(cols[1]), tasks));
      }
    }
    return new Snapshot(statuses, assignees);
  }

  /** Same contract as {@code WireEnumConverter}: an unknown value is schema drift — fail loudly. */
  private static TaskStatus statusOf(Object raw) {
    TaskStatus status = WireEnum.fromValue(TaskStatus.class, String.valueOf(raw));
    if (status == null) {
      throw new IllegalArgumentException("Unknown TaskStatus value: " + raw);
    }
    return status;
  }
}
