package com.kanban.modules.notification;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

public class NotificationQueriesImpl implements NotificationQueries {
  @PersistenceContext
  private EntityManager em;

  @Override
  public Page<Notification> findByRecipientFiltered(String recipientId, Boolean isRead, NotificationType type,
      Pageable pageable) {
    StringBuilder where = new StringBuilder(" where n.recipientId = :recipientId");
    if (isRead != null) {
      where.append(" and n.isRead = :isRead");
    }
    if (type != null) {
      where.append(" and n.type = :type");
    }
    TypedQuery<Notification> data = em.createQuery(
        "select n from Notification n left join fetch n.actor" + where + " order by n.createdAt desc",
        Notification.class);
    TypedQuery<Long> count = em.createQuery("select count(n) from Notification n" + where, Long.class);
    for (TypedQuery<?> q : List.of(data, count)) {
      q.setParameter("recipientId", recipientId);
      if (isRead != null) {
        q.setParameter("isRead", isRead);
      }
      if (type != null) {
        q.setParameter("type", type);
      }
    }
    data.setFirstResult((int) pageable.getOffset());
    data.setMaxResults(pageable.getPageSize());
    return new PageImpl<>(data.getResultList(), pageable, count.getSingleResult());
  }
}
