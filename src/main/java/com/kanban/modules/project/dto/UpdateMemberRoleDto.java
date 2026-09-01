package com.kanban.modules.project.dto;

import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.project.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;

public class UpdateMemberRoleDto extends ValidatedDto {
  @Schema(example = "admin", description = "New role for the member")
  @IsEnum(ProjectRole.class)
  public ProjectRole role;
}
