package com.kanban.modules.project.dto;

import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

/** PartialType(CreateProjectDto) */
public class UpdateProjectDto extends ValidatedDto {
  @Schema(example = "My Kanban Board")
  @IsOptional
  @IsString
  @IsNotEmpty
  @MaxLength(100)
  public String name;

  @Schema(example = "A project for tracking tasks")
  @IsOptional
  @IsString
  public String description;
}
