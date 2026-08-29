package com.kanban.modules.notification;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.notification.dto.NotificationQueryDto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {
  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private final NotificationRepository notificationRepository;

  public NotificationService(NotificationRepository notificationRepository) {
    this.notificationRepository = notificationRepository;
  }

  /** Data for a notification to persist. */
  public record NewNotification(NotificationType type, String recipient_id, String actor_id, String entity_type,
      String entity_id, Map<String, Object> payload) {}

  public Notification create(NewNotification data) {
    // Don't notify yourself
    if (data.recipient_id().equals(data.actor_id())) {
      return null;
    }
    return notificationRepository.save(toEntity(data));
  }

  public List<Notification> createBatch(List<NewNotification> notifications) {
    List<Notification> entities = new ArrayList<>();
    for (NewNotification n : notifications) {
      if (!n.recipient_id().equals(n.actor_id())) {
        entities.add(toEntity(n));
      }
    }
    if (entities.isEmpty()) {
      return List.of();
    }
    return notificationRepository.saveAll(entities);
  }

  public PaginatedResponse<Map<String, Object>> findByRecipient(String recipientId, NotificationQueryDto query) {
    try {
      int page = query.page == null ? 1 : query.page;
      int limit = query.limit == null ? 20 : query.limit;
      Page<Notification> result = notificationRepository.findByRecipientFiltered(recipientId, query.is_read,
          query.type, PageRequest.of(page - 1, limit));
      return new PaginatedResponse<>(result.getContent().stream().map(Notification::toJsonWithActor).toList(),
          PaginationMeta.of(page, limit, result.getTotalElements()));
    } catch (RuntimeException e) {
      log.error("Failed to fetch notifications", e);
      throw internal("Failed to fetch notifications", e);
    }
  }

  public Map<String, Object> markAsRead(String recipientId, List<String> notificationIds) {
    if (notificationIds.isEmpty()) {
      return Json.map("updated", 0);
    }
    try {
      int updated = notificationRepository.markAsRead(recipientId, notificationIds, Instant.now());
      return Json.map("updated", updated);
    } catch (RuntimeException e) {
      log.error("Failed to mark notifications as read", e);
      throw internal("Failed to mark notifications as read", e);
    }
  }

  public Map<String, Object> markAllAsRead(String recipientId) {
    try {
      int updated = notificationRepository.markAllAsRead(recipientId, Instant.now());
      return Json.map("updated", updated);
    } catch (RuntimeException e) {
      log.error("Failed to mark all notifications as read", e);
      throw internal("Failed to mark all notifications as read", e);
    }
  }

  public Map<String, Object> getUnreadCount(String recipientId) {
    try {
      return Json.map("count", notificationRepository.countByRecipientIdAndIsReadFalse(recipientId));
    } catch (RuntimeException e) {
      log.error("Failed to get unread count", e);
      throw internal("Failed to get unread count", e);
    }
  }

  public void remove(String id, String recipientId) {
    try {
      Notification notification = notificationRepository.findByIdAndRecipientId(id, recipientId)
          .orElseThrow(() -> new NotFoundException(Json.map(
              "statusCode", 404,
              "message", "Notification with id \"" + id + "\" not found")));
      notificationRepository.deleteById(notification.getId());
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to delete notification", e);
      throw internal("Failed to delete notification", e);
    }
  }

  private static Notification toEntity(NewNotification n) {
    Notification entity = new Notification();
    entity.setType(n.type());
    entity.setRecipientId(n.recipient_id());
    entity.setActorId(n.actor_id());
    entity.setEntityType(n.entity_type());
    entity.setEntityId(n.entity_id());
    entity.setPayload(n.payload());
    return entity;
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
