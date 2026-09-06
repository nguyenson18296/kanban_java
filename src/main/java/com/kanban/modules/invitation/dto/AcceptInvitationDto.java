package com.kanban.modules.invitation.dto;

import com.kanban.common.validation.IsString;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.MinLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class AcceptInvitationDto extends ValidatedDto {
  @Schema(description = "Raw invitation token from the invite link (64 hex chars)", example = "3f1a...")
  @IsString
  @MinLength(64)
  @MaxLength(64)
  public String token;
}
