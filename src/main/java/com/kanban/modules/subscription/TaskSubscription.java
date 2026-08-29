package com.kanban.modules.subscription;

import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "task_subscriptions")
@IdClass(TaskSubscriptionId.class)
public class TaskSubscription {
  @Id
  @Column(name = "task_id", columnDefinition = "uuid")
  private String taskId;

  @Id
  @Column(name = "user_id", columnDefinition = "uuid")
  private String userId;

  @Convert(converter = SubscriptionSourceConverter.class)
  @Column(nullable = false, columnDefinition = "task_subscription_source")
  private SubscriptionSource source = SubscriptionSource.MANUAL;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", insertable = false, updatable = false)
  private User user;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public TaskSubscription() {}

  public TaskSubscription(String taskId, String userId, SubscriptionSource source, User user, Instant createdAt) {
    this.taskId = taskId;
    this.userId = userId;
    this.source = source;
    this.user = user;
    this.createdAt = createdAt;
  }

  public String getTaskId() { return taskId; }
  public void setTaskId(String taskId) { this.taskId = taskId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public SubscriptionSource getSource() { return source; }
  public void setSource(SubscriptionSource source) { this.source = source; }
  public User getUser() { return user; }
  public void setUser(User user) { this.user = user; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
