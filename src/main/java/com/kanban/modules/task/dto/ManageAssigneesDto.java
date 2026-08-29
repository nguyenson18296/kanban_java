package com.kanban.modules.task.dto;

import com.kanban.common.validation.ArrayNotEmpty;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class ManageAssigneesDto extends ValidatedDto {
  @Schema(description = "Array of user UUIDs")
  @IsArray
  @ArrayNotEmpty
  @IsUUID(version = "4", each = true)
  public List<String> user_ids;
}
