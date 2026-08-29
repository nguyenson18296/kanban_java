package com.kanban.modules.comment.dto;

import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.Max;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.TransformWith;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class CommentQueryDto extends ValidatedDto {
  @Schema(example = "1", description = "Page number (1-based)", defaultValue = "1")
  @IsOptional
  @TransformWith(ParseIntTransformer.class)
  @IsInt
  @Min(1)
  public Integer page = 1;

  @Schema(example = "20", description = "Items per page", defaultValue = "20")
  @IsOptional
  @TransformWith(ParseIntTransformer.class)
  @IsInt
  @Min(1)
  @Max(100)
  public Integer limit = 20;

  @Schema(description = "Sort by created_at", defaultValue = "DESC")
  @IsOptional
  @IsEnum(CommentSortOrder.class)
  public CommentSortOrder sort = CommentSortOrder.DESC;
}
