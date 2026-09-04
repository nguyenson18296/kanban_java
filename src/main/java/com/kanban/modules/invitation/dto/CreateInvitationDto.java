package com.kanban.modules.invitation.dto;

import com.kanban.common.validation.IsEmail;
import com.kanban.common.validation.IsIn;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.project.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateInvitationDto extends ValidatedDto {
  @Schema(example = "jane@example.com")
  @IsEmail
  public String email;

  @Schema(
      example = "member",
      allowableValues = {"admin", "member", "viewer"},
      description = "Role granted on acceptance. Owner cannot be granted by invitation.")
  @IsOptional
  @IsIn({"admin", "member", "viewer"})
  public ProjectRole role;
}
