package com.kanban.modules.task;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
public class JpaTaskPositionFunctions implements TaskPositionFunctions {
  @PersistenceContext
  private EntityManager em;

  @Override
  public void moveTask(String taskId, int columnId, int position) {
    em.createNativeQuery("SELECT fn_move_task(CAST(:id AS uuid), CAST(:col AS int), CAST(:pos AS int))")
        .setParameter("id", taskId).setParameter("col", columnId).setParameter("pos", position)
        .getResultList();
  }

  @Override
  public void reorderTask(String taskId, int position) {
    em.createNativeQuery("SELECT fn_reorder_task(CAST(:id AS uuid), CAST(:pos AS int))")
        .setParameter("id", taskId).setParameter("pos", position)
        .getResultList();
  }

  @Override
  public void reorderSubtask(String subtaskId, String parentId, int position) {
    em.createNativeQuery(
        "SELECT fn_reorder_subtask(CAST(:sub AS uuid), CAST(:parent AS uuid), CAST(:pos AS int))")
        .setParameter("sub", subtaskId).setParameter("parent", parentId).setParameter("pos", position)
        .getResultList();
  }
}
