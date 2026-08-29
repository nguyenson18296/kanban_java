package com.kanban.modules.auth.dto;

import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuthResponseDto(
    @Schema(example = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...") String access_token,
    @Schema(description = "Opaque refresh token (base64url-encoded)",
        example = "V2tYcGRhQmkzNU1MaHFGZ0tEMklBZnRITkVsN3B6cHI") String refresh_token,
    AuthUserDto user) {

  public record AuthUserDto(
      @Schema(example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") String id,
      @Schema(example = "john@example.com") String email,
      @Schema(example = "John Doe") String full_name,
      UserRole role,
      @Schema(example = "https://api.dicebear.com/9.x/initials/svg?seed=JD") String avatar_url) {

    public static AuthUserDto of(User user) {
      return new AuthUserDto(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getAvatarUrl());
    }
  }
}
