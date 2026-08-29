package com.kanban.modules.comment;

import com.kanban.common.json.Json;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "task_comments")
public class Comment {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(nullable = false, columnDefinition = "text")
  private String content;

  @Column(name = "is_edited", nullable = false)
  private boolean isEdited = false;

  @Column(name = "task_id", nullable = false, columnDefinition = "uuid")
  private String taskId;

  @Column(name = "author_id", nullable = false, columnDefinition = "uuid")
  private String authorId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "author_id", insertable = false, updatable = false)
  private User author;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** TypeORM {@code toJSON()} drops author_id; author relation always loaded by the service. */
  public Map<String, Object> toJson() {
    return Json.map(
        "id", id,
        "content", content,
        "is_edited", isEdited,
        "task_id", taskId,
        "author", author == null ? null : author.toJson(),
        "created_at", createdAt,
        "updated_at", updatedAt);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  public boolean isEdited() { return isEdited; }
  public void setEdited(boolean edited) { isEdited = edited; }
  public String getTaskId() { return taskId; }
  public void setTaskId(String taskId) { this.taskId = taskId; }
  public String getAuthorId() { return authorId; }
  public void setAuthorId(String authorId) { this.authorId = authorId; }
  public User getAuthor() { return author; }
  public void setAuthor(User author) { this.author = author; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
