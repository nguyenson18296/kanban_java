package com.kanban.modules.notification;

import com.kanban.common.json.Json;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notifications")
public class Notification {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Convert(converter = NotificationTypeConverter.class)
  @Column(nullable = false, columnDefinition = "notifications_type_enum")
  private NotificationType type;

  @Column(name = "recipient_id", nullable = false, columnDefinition = "uuid")
  private String recipientId;

  @Column(name = "actor_id", nullable = false, columnDefinition = "uuid")
  private String actorId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id", insertable = false, updatable = false)
  private User actor;

  @Column(name = "entity_type", nullable = false, length = 50)
  private String entityType;

  @Column(name = "entity_id", nullable = false, columnDefinition = "uuid")
  private String entityId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> payload = new LinkedHashMap<>();

  @Column(name = "is_read", nullable = false)
  private boolean isRead = false;

  @Column(name = "read_at")
  private Instant readAt;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** TypeORM {@code toJSON()} drops recipient_id and actor_id; actor is loaded by the list query. */
  public Map<String, Object> toJsonWithActor() {
    return Json.map(
        "id", id,
        "type", type,
        "actor", actor == null ? null : actor.toJson(),
        "entity_type", entityType,
        "entity_id", entityId,
        "payload", payload,
        "is_read", isRead,
        "read_at", readAt,
        "created_at", createdAt);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public NotificationType getType() { return type; }
  public void setType(NotificationType type) { this.type = type; }
  public String getRecipientId() { return recipientId; }
  public void setRecipientId(String recipientId) { this.recipientId = recipientId; }
  public String getActorId() { return actorId; }
  public void setActorId(String actorId) { this.actorId = actorId; }
  public User getActor() { return actor; }
  public void setActor(User actor) { this.actor = actor; }
  public String getEntityType() { return entityType; }
  public void setEntityType(String entityType) { this.entityType = entityType; }
  public String getEntityId() { return entityId; }
  public void setEntityId(String entityId) { this.entityId = entityId; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public boolean isRead() { return isRead; }
  public void setRead(boolean read) { isRead = read; }
  public Instant getReadAt() { return readAt; }
  public void setReadAt(Instant readAt) { this.readAt = readAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
