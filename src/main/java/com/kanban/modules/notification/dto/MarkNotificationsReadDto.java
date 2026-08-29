package com.kanban.modules.notification.dto;

import com.kanban.common.validation.ArrayNotEmpty;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class MarkNotificationsReadDto extends ValidatedDto {
  @Schema(description = "Array of notification UUIDs to mark as read")
  @IsArray
  @ArrayNotEmpty
  @IsUUID(version = "4", each = true)
  public List<String> ids;
}
