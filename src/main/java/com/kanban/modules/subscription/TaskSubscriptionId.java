package com.kanban.modules.subscription;

import java.io.Serializable;
import java.util.Objects;

public class TaskSubscriptionId implements Serializable {
  private String taskId;
  private String userId;

  public TaskSubscriptionId() {}

  public TaskSubscriptionId(String taskId, String userId) {
    this.taskId = taskId;
    this.userId = userId;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof TaskSubscriptionId other && Objects.equals(taskId, other.taskId)
        && Objects.equals(userId, other.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(taskId, userId);
  }
}
