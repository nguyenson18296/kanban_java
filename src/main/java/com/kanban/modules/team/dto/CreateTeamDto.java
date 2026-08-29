package com.kanban.modules.team.dto;

import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateTeamDto extends ValidatedDto {
  @Schema(example = "Backend Team")
  @IsString
  @IsNotEmpty
  @MaxLength(100)
  public String name;

  @Schema(example = "Handles server-side development")
  @IsOptional
  @IsString
  public String description;

  @Schema(example = "#3B82F6")
  @IsOptional
  @IsString
  @MaxLength(20)
  public String color;
}
