package com.kanban.modules.team;

import java.io.Serializable;
import java.util.Objects;

public class TeamMemberId implements Serializable {
  private Integer teamId;
  private String userId;

  public TeamMemberId() {}

  public TeamMemberId(Integer teamId, String userId) {
    this.teamId = teamId;
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TeamMemberId other && Objects.equals(teamId, other.teamId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(teamId, userId);
  }
}
