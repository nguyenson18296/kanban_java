package com.kanban.modules.auth.dto;

import com.kanban.common.validation.IsEmail;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class LoginDto extends ValidatedDto {
  @Schema(example = "john@example.com")
  @IsEmail
  public String email;

  @Schema(example = "P@ssw0rd!")
  @IsString
  public String password;
}
