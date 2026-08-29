package com.kanban.modules.label;

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
@Table(name = "labels")
public class Label {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;

  @Column(nullable = false, length = 50, unique = true)
  private String name;

  @Column(nullable = false, length = 20)
  private String color;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public Label() {}

  public Label(Integer id, String name, String color) {
    this.id = id;
    this.name = name;
    this.color = color;
  }

  public Map<String, Object> toJson() {
    return Json.map(
        "id", id,
        "name", name,
        "color", color,
        "created_at", createdAt,
        "updated_at", updatedAt);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Label other && id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return id == null ? System.identityHashCode(this) : id.hashCode();
  }

  public Integer getId() { return id; }
  public void setId(Integer id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getColor() { return color; }
  public void setColor(String color) { this.color = color; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
