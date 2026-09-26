package com.kanban.modules.dependency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

/** One directed edge: {@code blockingTaskId} blocks {@code blockedTaskId}. */
@Entity
@Table(name = "task_dependencies")
@IdClass(TaskDependencyId.class)
public class TaskDependency {
  @Id
  @Column(name = "blocking_task_id", columnDefinition = "uuid")
  private String blockingTaskId;

  @Id
  @Column(name = "blocked_task_id", columnDefinition = "uuid")
  private String blockedTaskId;

  @Column(name = "created_by", columnDefinition = "uuid")
  private String createdBy;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public TaskDependency() {}

  public TaskDependency(String blockingTaskId, String blockedTaskId, String createdBy, Instant createdAt) {
    this.blockingTaskId = blockingTaskId;
    this.blockedTaskId = blockedTaskId;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  public String getBlockingTaskId() { return blockingTaskId; }
  public void setBlockingTaskId(String blockingTaskId) { this.blockingTaskId = blockingTaskId; }
  public String getBlockedTaskId() { return blockedTaskId; }
  public void setBlockedTaskId(String blockedTaskId) { this.blockedTaskId = blockedTaskId; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
