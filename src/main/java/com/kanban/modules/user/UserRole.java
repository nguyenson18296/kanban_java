package com.kanban.modules.user;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum UserRole implements WireEnum {
  BACKEND_DEVELOPER("backend_developer"),
  FRONTEND_DEVELOPER("frontend_developer"),
  FULLSTACK_DEVELOPER("fullstack_developer"),
  QA("qa"),
  DEVOPS("devops"),
  DESIGNER("designer"),
  PRODUCT_MANAGER("product_manager"),
  TECH_LEAD("tech_lead");

  private final String value;

  UserRole(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
