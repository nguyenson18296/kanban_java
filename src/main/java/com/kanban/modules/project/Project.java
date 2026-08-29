package com.kanban.modules.project;

import com.kanban.common.json.Json;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "projects")
public class Project {
  private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  @Id
  @Column(length = 8)
  private String id;

  @Column(nullable = false, length = 100, unique = true)
  private String name;

  @Column(nullable = false, length = 10, unique = true)
  private String tag;

  @Column(name = "ticket_counter", nullable = false)
  private int ticketCounter = 0;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "created_by", columnDefinition = "uuid")
  private String createdBy;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by", insertable = false, updatable = false)
  private User creator;

  /** TypeORM {@code @BeforeInsert} — generate an 8-char alphanumeric id. */
  @PrePersist
  public void generateId() {
    if (id == null) {
      id = generateAlphanumericId(8);
    }
  }

  public static String generateAlphanumericId(int length) {
    byte[] bytes = new byte[length];
    RANDOM.nextBytes(bytes);
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(ID_CHARS.charAt((bytes[i] & 0xff) % ID_CHARS.length()));
    }
    return sb.toString();
  }

  /**
   * TypeORM {@code toJSON()}: drops ticket_counter and created_by. The creator
   * relation is included only when it was loaded ({@code withCreator}).
   */
  public Map<String, Object> toJson(boolean withCreator) {
    Map<String, Object> json = Json.map(
        "id", id,
        "name", name,
        "tag", tag,
        "description", description,
        "created_at", createdAt,
        "updated_at", updatedAt);
    if (withCreator) {
      json.put("creator", creator == null ? null : creator.toJson());
    }
    return json;
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getTag() { return tag; }
  public void setTag(String tag) { this.tag = tag; }
  public int getTicketCounter() { return ticketCounter; }
  public void setTicketCounter(int ticketCounter) { this.ticketCounter = ticketCounter; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
  public User getCreator() { return creator; }
  public void setCreator(User creator) { this.creator = creator; }
}
