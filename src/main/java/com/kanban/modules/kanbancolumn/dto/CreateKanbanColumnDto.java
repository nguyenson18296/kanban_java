package com.kanban.modules.kanbancolumn.dto;

import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.Matches;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateKanbanColumnDto extends ValidatedDto {
  @Schema(example = "In Progress")
  @IsString
  @IsNotEmpty
  @MaxLength(100)
  public String name;

  @Schema(example = "aB3kM9xZ")
  @IsString
  @IsNotEmpty
  @Matches(value = "^[A-Za-z0-9]{8}$", message = "project_id must be exactly 8 alphanumeric characters")
  public String project_id;

  @Schema(example = "2")
  @IsOptional
  @IsInt
  @Min(0)
  public Integer position;

  @Schema(example = "#F59E0B")
  @IsOptional
  @IsString
  @MaxLength(20)
  @Matches(value = "^#[0-9A-Fa-f]{6}$", message = "color must be a valid 6-digit hex color (e.g., #FF5733)")
  public String color;
}
