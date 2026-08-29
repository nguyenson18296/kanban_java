package com.kanban.modules.kanbancolumn;

import com.kanban.common.json.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "kanban_columns")
public class KanbanColumn {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(nullable = false, length = 100, unique = true)
  private String name;

  @Column(nullable = false)
  private int position = 0;

  @Column(length = 20)
  private String color;

  @Column(name = "is_archived", nullable = false)
  private boolean isArchived = false;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "project_id", nullable = false, length = 8)
  private String projectId;

  public Map<String, Object> toJson() {
    return Json.map(
        "id", id,
        "name", name,
        "position", position,
        "color", color,
        "is_archived", isArchived,
        "created_at", createdAt,
        "updated_at", updatedAt,
        "project_id", projectId);
  }

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public int getPosition() { return position; }
  public void setPosition(int position) { this.position = position; }
  public String getColor() { return color; }
  public void setColor(String color) { this.color = color; }
  public boolean isArchived() { return isArchived; }
  public void setArchived(boolean archived) { isArchived = archived; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
}
