package com.kanban.modules.project;

import java.io.Serializable;
import java.util.Objects;

public class ProjectMemberId implements Serializable {
  private String projectId;
  private String userId;

  public ProjectMemberId() {}

  public ProjectMemberId(String projectId, String userId) {
    this.projectId = projectId;
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ProjectMemberId other && Objects.equals(projectId, other.projectId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(projectId, userId);
  }
}
