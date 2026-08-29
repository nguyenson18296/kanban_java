package com.kanban.modules.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Dynamic-where paginated lookup (TypeORM findAndCount with an optional where object). */
public interface NotificationQueries {
  Page<Notification> findByRecipientFiltered(String recipientId, Boolean isRead, NotificationType type, Pageable pageable);
}
