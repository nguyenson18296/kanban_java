package com.kanban.modules.user;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class UserRoleConverter extends WireEnumConverter<UserRole> {
  public UserRoleConverter() {
    super(UserRole.class);
  }
}
