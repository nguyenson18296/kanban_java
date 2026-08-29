package com.kanban.modules.comment.dto;

import com.kanban.common.validation.WireEnum;

public enum CommentSortOrder implements WireEnum {
  ASC("ASC"),
  DESC("DESC");

  private final String value;

  CommentSortOrder(String value) {
    this.value = value;
  }

  @Override
  public String value() {
    return value;
  }
}
