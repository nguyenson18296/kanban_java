package com.kanban.modules.project;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum ProjectRole implements WireEnum {
  OWNER("owner", 3),
  ADMIN("admin", 2),
  MEMBER("member", 1),
  VIEWER("viewer", 0);

  private final String value;
  private final int rank;

  ProjectRole(String value, int rank) {
    this.value = value;
    this.rank = rank;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }

  /** PROJECT_ROLE_HIERARCHY */
  public int rank() {
    return rank;
  }
}
