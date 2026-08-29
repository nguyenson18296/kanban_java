package com.kanban.modules.auth.dto;

import com.kanban.common.validation.IsEmail;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.MinLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class RegisterDto extends ValidatedDto {
  @Schema(example = "john@example.com")
  @IsEmail
  public String email;

  @Schema(example = "P@ssw0rd!", minLength = 8, maxLength = 72)
  @IsString
  @MinLength(8)
  @MaxLength(72)
  public String password;

  @Schema(example = "John Doe", minLength = 1, maxLength = 150)
  @IsString
  @MinLength(1)
  @MaxLength(150)
  public String full_name;
}
