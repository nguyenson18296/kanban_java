package com.kanban.modules.dependency.dto;

import com.kanban.common.validation.ArrayMaxSize;
import com.kanban.common.validation.ArrayNotEmpty;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class ManageDependenciesDto extends ValidatedDto {
  @Schema(description = "UUIDs of the tasks that block this task")
  @IsArray
  @ArrayNotEmpty
  @ArrayMaxSize(50)
  @IsUUID(version = "4", each = true)
  public List<String> blocked_by_ids;

  public ManageDependenciesDto() {}

  /** Test convenience: marks the key present the way the resolver would. */
  public ManageDependenciesDto(List<String> blockedByIds) {
    this.blocked_by_ids = blockedByIds;
    with("blocked_by_ids");
  }
}
