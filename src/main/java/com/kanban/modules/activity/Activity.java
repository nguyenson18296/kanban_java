package com.kanban.modules.activity;

import com.kanban.common.json.Json;
import com.kanban.modules.activity.events.TaskActivityAction;
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
@Table(name = "task_activities")
public class Activity {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
  private String taskId;

  @Column(name = "actor_id", nullable = false, columnDefinition = "uuid")
  private String actorId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "actor_id", insertable = false, updatable = false)
  private User actor;

  @Convert(converter = TaskActivityActionConverter.class)
  @Column(nullable = false, columnDefinition = "task_activity_action")
  private TaskActivityAction action;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> payload = new LinkedHashMap<>();

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** TypeORM {@code toJSON()} drops task_id and actor_id. */
  public Map<String, Object> toJsonWithActor() {
    return Json.map(
        "id", id,
        "actor", actor == null ? null : actor.toJson(),
        "action", action,
        "payload", payload,
        "created_at", createdAt);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTaskId() { return taskId; }
  public void setTaskId(String taskId) { this.taskId = taskId; }
  public String getActorId() { return actorId; }
  public void setActorId(String actorId) { this.actorId = actorId; }
  public User getActor() { return actor; }
  public void setActor(User actor) { this.actor = actor; }
  public TaskActivityAction getAction() { return action; }
  public void setAction(TaskActivityAction action) { this.action = action; }
  public Map<String, Object> getPayload() { return payload; }
  public void setPayload(Map<String, Object> payload) { this.payload = payload; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
