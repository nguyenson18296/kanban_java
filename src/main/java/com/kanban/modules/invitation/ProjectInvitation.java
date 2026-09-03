package com.kanban.modules.invitation;

import com.kanban.common.json.Json;
import com.kanban.modules.project.Project;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.project.ProjectRoleConverter;
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
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "project_invitations")
public class ProjectInvitation {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(name = "project_id", nullable = false, length = 8)
  private String projectId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", insertable = false, updatable = false)
  private Project project;

  @Column(nullable = false, length = 255)
  private String email;

  @Convert(converter = ProjectRoleConverter.class)
  @Column(nullable = false, columnDefinition = "project_role")
  private ProjectRole role = ProjectRole.MEMBER;

  /** Never serialized ({@code select: false} in TypeORM); loaded only to verify a token. */
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "invited_by", columnDefinition = "uuid")
  private String invitedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invited_by", insertable = false, updatable = false)
  private User inviter;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "accepted_by", columnDefinition = "uuid")
  private String acceptedBy;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public ProjectInvitation() {}

  /**
   * TypeORM {@code toJSON()}: drops token_hash, invited_by and accepted_by. The
   * inviter relation is included only when it was loaded ({@code withInviter}).
   */
  public Map<String, Object> toJson(boolean withInviter) {
    Map<String, Object> json = Json.map(
        "id", id,
        "project_id", projectId,
        "email", email,
        "role", role,
        "expires_at", expiresAt,
        "accepted_at", acceptedAt,
        "revoked_at", revokedAt,
        "created_at", createdAt);
    if (withInviter) {
      json.put("inviter", inviter == null ? null : inviter.toJson());
    }
    return json;
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public Project getProject() { return project; }
  public void setProject(Project project) { this.project = project; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public ProjectRole getRole() { return role; }
  public void setRole(ProjectRole role) { this.role = role; }
  public String getTokenHash() { return tokenHash; }
  public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
  public String getInvitedBy() { return invitedBy; }
  public void setInvitedBy(String invitedBy) { this.invitedBy = invitedBy; }
  public User getInviter() { return inviter; }
  public void setInviter(User inviter) { this.inviter = inviter; }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
  public Instant getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
  public String getAcceptedBy() { return acceptedBy; }
  public void setAcceptedBy(String acceptedBy) { this.acceptedBy = acceptedBy; }
  public Instant getRevokedAt() { return revokedAt; }
  public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
