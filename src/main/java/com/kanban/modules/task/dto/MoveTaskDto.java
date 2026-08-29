package com.kanban.modules.task.dto;

import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class MoveTaskDto extends ValidatedDto {
  @Schema(example = "3", description = "Target column ID")
  @IsInt
  public Integer column_id;

  @Schema(example = "0", description = "Position within the target column")
  @IsInt
  @Min(0)
  public Integer position;
}
