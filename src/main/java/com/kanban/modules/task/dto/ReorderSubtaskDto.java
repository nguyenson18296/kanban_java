package com.kanban.modules.task.dto;

import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class ReorderSubtaskDto extends ValidatedDto {
  @Schema(example = "1", description = "New position within the parent task")
  @IsInt
  @Min(0)
  public Integer position;
}
