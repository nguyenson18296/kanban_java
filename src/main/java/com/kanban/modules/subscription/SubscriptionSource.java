package com.kanban.modules.subscription;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum SubscriptionSource implements WireEnum {
  ASSIGNED("assigned"),
  MENTIONED("mentioned"),
  COMMENTED("commented"),
  MANUAL("manual"),
  CREATED("created");

  private final String value;

  SubscriptionSource(String value) {
    this.value = value;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }
}
