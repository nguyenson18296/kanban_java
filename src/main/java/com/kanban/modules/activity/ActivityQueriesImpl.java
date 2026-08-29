package com.kanban.modules.activity;

import com.kanban.modules.activity.events.TaskActivityAction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class ActivityQueriesImpl implements ActivityQueries {
  @PersistenceContext
  private EntityManager em;

  @Override
  public Page<Activity> findByTaskFiltered(String taskId, TaskActivityAction action, Pageable pageable) {
    String where = " where a.taskId = :taskId" + (action != null ? " and a.action = :action" : "");
    TypedQuery<Activity> data = em.createQuery(
        "select a from Activity a left join fetch a.actor" + where + " order by a.createdAt asc", Activity.class);
    TypedQuery<Long> count = em.createQuery("select count(a) from Activity a" + where, Long.class);
    for (TypedQuery<?> q : List.of(data, count)) {
      q.setParameter("taskId", taskId);
      if (action != null) {
        q.setParameter("action", action);
      }
    }
    data.setFirstResult((int) pageable.getOffset());
    data.setMaxResults(pageable.getPageSize());
    return new PageImpl<>(data.getResultList(), pageable, count.getSingleResult());
  }
}
