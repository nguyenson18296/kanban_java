package com.kanban.modules.board.dto;

import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.Max;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.TypeNumber;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.task.TaskPriority;
import io.swagger.v3.oas.annotations.media.Schema;

/** Known camelCase query surface — kept as-is for wire compatibility. */
public class BoardQueryDto extends ValidatedDto {
  @Schema(description = "Max tasks returned per column", defaultValue = "50", minimum = "1", maximum = "200")
  @IsOptional
  @TypeNumber
  @IsInt
  @Min(1)
  @Max(200)
  public Integer tasksPerColumn = 50;

  @Schema(description = "Filter tasks by assignee UUID")
  @IsOptional
  @IsUUID
  public String assigneeId;

  @Schema(description = "Filter tasks by priority")
  @IsOptional
  @IsEnum(TaskPriority.class)
  public TaskPriority priority;

  @Schema(description = "Filter tasks by label ID")
  @IsOptional
  @TypeNumber
  @IsInt
  public Integer labelId;

  @Schema(description = "Full-text search over task title and description (whole words, websearch syntax)")
  @IsOptional
  @IsString
  @MaxLength(200)
  public String search;
}
