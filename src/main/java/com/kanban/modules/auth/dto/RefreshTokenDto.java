package com.kanban.modules.auth.dto;

import com.kanban.common.validation.IsString;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class RefreshTokenDto extends ValidatedDto {
  @Schema(description = "Opaque refresh token (base64url-encoded)",
      example = "V2tYcGRhQmkzNU1MaHFGZ0tEMklBZnRITkVsN3B6cHI")
  @IsString
  public String refresh_token;
}
