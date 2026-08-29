package com.kanban.modules.comment.dto;

import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.TransformWith;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateCommentDto extends ValidatedDto {
  @Schema(example = "<p>This looks good!</p>", description = "HTML content of the comment (will be sanitized)")
  @IsString
  @IsNotEmpty
  @TransformWith(SanitizeTransformer.class)
  public String content;

  public CreateCommentDto() {}

  public CreateCommentDto(String content) {
    this.content = content;
    with("content");
  }
}
