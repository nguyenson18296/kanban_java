package com.kanban.modules.presence.dto;

import com.kanban.common.validation.ArrayMaxSize;
import com.kanban.common.validation.ArrayMinSize;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.TransformWith;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class GetPresenceQueryDto extends ValidatedDto {
  @Schema(description = "Comma-separated list of user UUIDs (max 100)", example = "a1b2c3d4-...,b2c3d4e5-...")
  @TransformWith(UserIdsTransformer.class)
  @IsArray
  @ArrayMinSize(1)
  @ArrayMaxSize(100)
  @IsUUID(version = "4", each = true)
  public List<String> userIds;
}
