package com.kanban.modules.label.dto;

import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.Matches;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateLabelDto extends ValidatedDto {
  @Schema(example = "Bug")
  @IsString
  @IsNotEmpty
  @MaxLength(50)
  public String name;

  @Schema(example = "#EF4444")
  @IsString
  @IsNotEmpty
  @MaxLength(20)
  @Matches(value = "^#[0-9A-Fa-f]{6}$", message = "color must be a valid 6-digit hex color (e.g., #FF5733)")
  public String color;
}
