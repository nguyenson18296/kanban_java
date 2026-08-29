package com.kanban.modules.user;

import com.kanban.common.json.Json;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "users")
public class User {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(nullable = false, length = 255, unique = true)
  private String email;

  @Column(name = "full_name", nullable = false, length = 150)
  private String fullName;

  /** Never serialized ({@code select: false} in TypeORM); loaded only for login. */
  @Column(name = "password_hash", nullable = false, columnDefinition = "text")
  private String passwordHash;

  @Convert(converter = UserRoleConverter.class)
  @Column(nullable = false, columnDefinition = "users_role_enum")
  private UserRole role = UserRole.BACKEND_DEVELOPER;

  @Column(name = "avatar_url", nullable = false, columnDefinition = "text")
  private String avatarUrl = "https://api.dicebear.com/9.x/initials/svg?seed=default";

  @Column(name = "is_active", nullable = false)
  private boolean isActive = true;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp(source = SourceType.DB)
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public User() {}

  public User(String id, String email, String fullName, UserRole role, String avatarUrl, boolean isActive) {
    this.id = id;
    this.email = email;
    this.fullName = fullName;
    this.role = role;
    this.avatarUrl = avatarUrl;
    this.isActive = isActive;
  }

  /** TypeORM JSON shape (password_hash is never included). */
  public Map<String, Object> toJson() {
    return Json.map(
        "id", id,
        "email", email,
        "full_name", fullName,
        "role", role,
        "avatar_url", avatarUrl,
        "is_active", isActive,
        "created_at", createdAt,
        "updated_at", updatedAt);
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public String getFullName() { return fullName; }
  public void setFullName(String fullName) { this.fullName = fullName; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public UserRole getRole() { return role; }
  public void setRole(UserRole role) { this.role = role; }
  public String getAvatarUrl() { return avatarUrl; }
  public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
  public boolean isActive() { return isActive; }
  public void setActive(boolean active) { isActive = active; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
