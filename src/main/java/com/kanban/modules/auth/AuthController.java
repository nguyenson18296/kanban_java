package com.kanban.modules.auth;

import com.kanban.common.json.Json;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.dto.AuthResponseDto;
import com.kanban.modules.auth.dto.LoginDto;
import com.kanban.modules.auth.dto.RefreshTokenDto;
import com.kanban.modules.auth.dto.RegisterDto;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth")
@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Register a new user")
  @ApiResponse(responseCode = "201", description = "User registered")
  @ApiResponse(responseCode = "409", description = "Email already exists")
  public AuthResponseDto register(@ValidatedBody RegisterDto dto, HttpServletRequest req) {
    return authService.register(dto, req.getRemoteAddr(), req.getHeader("user-agent"));
  }

  @PostMapping("/login")
  @Operation(summary = "Login with email and password")
  @ApiResponse(responseCode = "200", description = "Login successful")
  @ApiResponse(responseCode = "401", description = "Invalid credentials")
  public AuthResponseDto login(@ValidatedBody LoginDto dto, HttpServletRequest req) {
    return authService.login(dto, req.getRemoteAddr(), req.getHeader("user-agent"));
  }

  @PostMapping("/refresh")
  @Operation(summary = "Refresh access token")
  @ApiResponse(responseCode = "200", description = "Token refreshed")
  @ApiResponse(responseCode = "401", description = "Invalid or expired refresh token")
  public AuthResponseDto refresh(@ValidatedBody RefreshTokenDto dto, HttpServletRequest req) {
    return authService.refresh(dto.refresh_token, req.getRemoteAddr(), req.getHeader("user-agent"));
  }

  @PostMapping("/logout")
  @Operation(summary = "Logout and revoke refresh token")
  @ApiResponse(responseCode = "200", description = "Logged out")
  public Map<String, Object> logout(@ValidatedBody RefreshTokenDto dto) {
    boolean revoked = authService.logout(dto.refresh_token);
    return Json.map(
        "message", revoked ? "Logged out successfully" : "Token not found or already revoked",
        "revoked", revoked);
  }

  @PostMapping("/logout-all")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Revoke all refresh tokens for current user")
  @ApiResponse(responseCode = "200", description = "All sessions revoked")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  public Map<String, Object> logoutAll(@CurrentUser("id") String userId) {
    int count = authService.logoutAll(userId);
    return Json.map("message", "All sessions revoked", "revoked_count", count);
  }

  @GetMapping("/me")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get current authenticated user profile")
  @ApiResponse(responseCode = "200", description = "Current user profile")
  @ApiResponse(responseCode = "401", description = "Unauthorized")
  public Map<String, Object> getProfile(@CurrentUser User user) {
    return user.toJson();
  }
}
