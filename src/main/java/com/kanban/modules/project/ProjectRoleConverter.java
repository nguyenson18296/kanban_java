package com.kanban.modules.project;

import com.kanban.modules.common.WireEnumConverter;
import jakarta.persistence.Converter;

@Converter
public class ProjectRoleConverter extends WireEnumConverter<ProjectRole> {
  public ProjectRoleConverter() {
    super(ProjectRole.class);
  }
}
