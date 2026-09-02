package com.kanban.modules.team.dto;

import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class AddTeamMemberDto extends ValidatedDto {
  @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890")
  @IsUUID
  public String user_id;
}
