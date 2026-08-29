package com.kanban.modules.notification;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface NotificationRepository extends JpaRepository<Notification, String>, NotificationQueries {
  @Transactional
  @Modifying
  @Query("update Notification n set n.isRead = true, n.readAt = :readAt"
      + " where n.id in :ids and n.recipientId = :recipientId and n.isRead = false")
  int markAsRead(@Param("recipientId") String recipientId, @Param("ids") Collection<String> ids,
      @Param("readAt") Instant readAt);

  @Transactional
  @Modifying
  @Query("update Notification n set n.isRead = true, n.readAt = :readAt"
      + " where n.recipientId = :recipientId and n.isRead = false")
  int markAllAsRead(@Param("recipientId") String recipientId, @Param("readAt") Instant readAt);

  long countByRecipientIdAndIsReadFalse(String recipientId);

  Optional<Notification> findByIdAndRecipientId(String id, String recipientId);
}
