package com.kanban.modules.team;

import com.kanban.common.json.Json;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

@Entity
@Table(name = "team_members")
@IdClass(TeamMemberId.class)
public class TeamMember {
  @Id
  @Column(name = "team_id")
  private Integer teamId;

  @Id
  @Column(name = "user_id", columnDefinition = "uuid")
  private String userId;

  @Column(name = "project_id", nullable = false, length = 8)
  private String projectId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", insertable = false, updatable = false)
  private User user;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "joined_at", nullable = false, updatable = false)
  private Instant joinedAt;

  public TeamMember() {}

  public TeamMember(Integer teamId, String userId, String projectId) {
    this.teamId = teamId;
    this.userId = userId;
    this.projectId = projectId;
  }

  public Map<String, Object> toJsonWithUser() {
    return Json.map(
        "team_id", teamId,
        "user_id", userId,
        "project_id", projectId,
        "user", user == null ? null : user.toJson(),
        "joined_at", joinedAt);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TeamMember other && Objects.equals(teamId, other.teamId)
        && Objects.equals(userId, other.userId) && Objects.equals(projectId, other.projectId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(teamId, userId, projectId);
  }

  @Override
  public String toString() {
    return "TeamMember{team_id=" + teamId + ", user_id=" + userId + ", project_id=" + projectId + "}";
  }

  public Integer getTeamId() { return teamId; }
  public void setTeamId(Integer teamId) { this.teamId = teamId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public User getUser() { return user; }
  public void setUser(User user) { this.user = user; }
  public Instant getJoinedAt() { return joinedAt; }
  public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
