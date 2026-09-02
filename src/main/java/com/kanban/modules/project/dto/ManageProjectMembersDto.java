package com.kanban.modules.project.dto;

import com.kanban.common.validation.ArrayNotEmpty;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class ManageProjectMembersDto extends ValidatedDto {
  @Schema(description = "Array of user UUIDs to add or remove", example = "[\"a1b2c3d4-e5f6-4890-abcd-ef1234567890\"]")
  @IsArray
  @ArrayNotEmpty
  @IsUUID(version = "4", each = true)
  public List<String> user_ids;
}
