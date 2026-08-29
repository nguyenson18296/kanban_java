package com.kanban.modules.notification.dto;

import com.kanban.common.validation.IsBoolean;
import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.Max;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.TransformWith;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.notification.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

public class NotificationQueryDto extends ValidatedDto {
  @Schema(example = "1", description = "Page number (1-based)", defaultValue = "1")
  @IsOptional
  @TransformWith(ParseIntIfStringTransformer.class)
  @IsInt
  @Min(1)
  public Integer page = 1;

  @Schema(example = "20", description = "Items per page", defaultValue = "20")
  @IsOptional
  @TransformWith(ParseIntIfStringTransformer.class)
  @IsInt
  @Min(1)
  @Max(100)
  public Integer limit = 20;

  @Schema(example = "false", description = "Filter by read status (true = read only, false = unread only, omit = all)")
  @IsOptional
  @TransformWith(BooleanStringTransformer.class)
  @IsBoolean
  public Boolean is_read;

  @Schema(description = "Filter by notification type")
  @IsOptional
  @IsEnum(NotificationType.class)
  public NotificationType type;
}
