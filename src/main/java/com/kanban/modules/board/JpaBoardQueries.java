package com.kanban.modules.board;

import com.kanban.modules.task.Task;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class JpaBoardQueries implements BoardQueries {
  @PersistenceContext
  private EntityManager em;

  private static String where(Filters f) {
    List<String> conditions = new ArrayList<>();
    conditions.add("t.column_id IN (:columnIds)");
    if (f.priority() != null) {
      conditions.add("t.priority = CAST(:priority AS tasks_priority_enum)");
    }
    if (f.search() != null) {
      conditions.add("t.title ILIKE :search");
    }
    if (f.assigneeId() != null) {
      conditions.add("t.id IN (SELECT task_id FROM task_assignees WHERE user_id = CAST(:assigneeId AS uuid))");
    }
    if (f.labelId() != null) {
      conditions.add("t.id IN (SELECT task_id FROM task_labels WHERE label_id = :labelId)");
    }
    return String.join(" AND ", conditions);
  }

  private static void bind(Query q, Filters f) {
    q.setParameter("columnIds", f.columnIds());
    if (f.priority() != null) {
      q.setParameter("priority", f.priority());
    }
    if (f.search() != null) {
      String escaped = f.search().replace("\\", "\\\\").replaceAll("[%_]", "\\\\$0");
      q.setParameter("search", "%" + escaped + "%");
    }
    if (f.assigneeId() != null) {
      q.setParameter("assigneeId", f.assigneeId());
    }
    if (f.labelId() != null) {
      q.setParameter("labelId", f.labelId());
    }
  }

  @Override
  public Map<Integer, Long> countPerColumn(Filters filters) {
    Query q = em.createNativeQuery(
        "SELECT t.column_id AS column_id, COUNT(*) AS task_count FROM tasks t WHERE " + where(filters)
            + " GROUP BY t.column_id");
    bind(q, filters);
    Map<Integer, Long> out = new LinkedHashMap<>();
    for (Object row : q.getResultList()) {
      Object[] cols = (Object[]) row;
      out.put(((Number) cols[0]).intValue(), ((Number) cols[1]).longValue());
    }
    return out;
  }

  @Override
  public List<Task> topTasksPerColumn(Filters filters, int tasksPerColumn) {
    Query idQuery = em.createNativeQuery(
        "SELECT ranked.id FROM (SELECT t.id, ROW_NUMBER() OVER (PARTITION BY t.column_id ORDER BY t.position ASC) AS rn"
            + " FROM tasks t WHERE " + where(filters) + ") ranked WHERE ranked.rn <= :tasksPerColumn");
    bind(idQuery, filters);
    idQuery.setParameter("tasksPerColumn", tasksPerColumn);
    List<String> ids = new ArrayList<>();
    for (Object row : idQuery.getResultList()) {
      ids.add(String.valueOf(row));
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    TypedQuery<Task> tasks = em.createQuery(
        "select distinct t from Task t left join fetch t.assignees left join fetch t.labels"
            + " where t.id in :ids order by t.columnId asc, t.position asc", Task.class);
    tasks.setParameter("ids", ids);
    return tasks.getResultList();
  }
}
