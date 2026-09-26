package com.kanban.modules.dependency;

import java.io.Serializable;
import java.util.Objects;

public class TaskDependencyId implements Serializable {
  private String blockingTaskId;
  private String blockedTaskId;

  public TaskDependencyId() {}

  public TaskDependencyId(String blockingTaskId, String blockedTaskId) {
    this.blockingTaskId = blockingTaskId;
    this.blockedTaskId = blockedTaskId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TaskDependencyId other && Objects.equals(blockingTaskId, other.blockingTaskId)
        && Objects.equals(blockedTaskId, other.blockedTaskId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(blockingTaskId, blockedTaskId);
  }
}
