package com.kanban.modules.project;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class JpaProjectAccessQueries implements ProjectAccessQueries {
  @PersistenceContext
  private EntityManager em;

  @Override
  public Optional<String> findProjectIdForTask(String taskId) {
    List<?> rows = em.createNativeQuery(
        "SELECT col.project_id FROM tasks task INNER JOIN kanban_columns col ON col.id = task.column_id "
            + "WHERE task.id = CAST(:taskId AS uuid)")
        .setParameter("taskId", taskId)
        .getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(String.valueOf(rows.get(0)));
  }

  @Override
  public Optional<String> findProjectIdForColumn(int columnId) {
    List<?> rows = em.createNativeQuery("SELECT col.project_id FROM kanban_columns col WHERE col.id = :columnId")
        .setParameter("columnId", columnId)
        .getResultList();
    return rows.isEmpty() ? Optional.empty() : Optional.of(String.valueOf(rows.get(0)));
  }
}
