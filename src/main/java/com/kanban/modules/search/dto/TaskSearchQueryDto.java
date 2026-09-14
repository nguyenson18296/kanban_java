package com.kanban.modules.search.dto;

import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.Max;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.TypeNumber;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class TaskSearchQueryDto extends ValidatedDto {
  @Schema(example = "login page",
      description = "Words to search for in task titles and descriptions (websearch syntax: \"quoted phrase\", "
          + "-excluded, or). Blank returns an empty result.")
  @IsOptional
  @IsString
  @MaxLength(200)
  public String q;

  @Schema(example = "1", description = "Page number (1-based)", defaultValue = "1")
  @IsOptional
  @TypeNumber
  @IsInt
  @Min(1)
  public Integer page = 1;

  @Schema(example = "20", description = "Items per page", defaultValue = "20")
  @IsOptional
  @TypeNumber
  @IsInt
  @Min(1)
  @Max(100)
  public Integer limit = 20;
}
