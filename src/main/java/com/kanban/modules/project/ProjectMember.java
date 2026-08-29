package com.kanban.modules.project;

import com.kanban.common.json.Json;
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
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "project_members")
@IdClass(ProjectMemberId.class)
public class ProjectMember {
  @Id
  @Column(name = "project_id", length = 8)
  private String projectId;

  @Id
  @Column(name = "user_id", columnDefinition = "uuid")
  private String userId;

  @Convert(converter = ProjectRoleConverter.class)
  @Column(nullable = false, columnDefinition = "project_role")
  private ProjectRole role = ProjectRole.MEMBER;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", insertable = false, updatable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", insertable = false, updatable = false)
  private Project project;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "joined_at", nullable = false, updatable = false)
  private Instant joinedAt;

  public ProjectMember() {}

  public ProjectMember(String projectId, String userId, ProjectRole role) {
    this.projectId = projectId;
    this.userId = userId;
    this.role = role;
  }

  /** JSON with the {@code user} relation loaded. */
  public Map<String, Object> toJsonWithUser() {
    return Json.map(
        "project_id", projectId,
        "user_id", userId,
        "role", role,
        "user", user == null ? null : user.toJson(),
        "joined_at", joinedAt);
  }

  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public ProjectRole getRole() { return role; }
  public void setRole(ProjectRole role) { this.role = role; }
  public User getUser() { return user; }
  public void setUser(User user) { this.user = user; }
  public Project getProject() { return project; }
  public void setProject(Project project) { this.project = project; }
  public Instant getJoinedAt() { return joinedAt; }
  public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
