package com.kanban.modules.user;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users")
@RestController
@RequestMapping("/users")
public class UserController {
  private final UserService userService;

  public UserController(UserService userService) {
    this.userService = userService;
  }

  @GetMapping
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get all users")
  @ApiResponse(responseCode = "200", description = "List of users")
  public List<Map<String, Object>> findAll() {
    return userService.findAll().stream().map(User::toJson).toList();
  }

  @GetMapping("/me/projects")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get projects for the authenticated user")
  @ApiResponse(responseCode = "200", description = "List of user projects")
  public ApiListResponse<Map<String, Object>> findMyProjects(@CurrentUser("id") String userId) {
    return userService.findProjects(userId, userId);
  }

  @GetMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get a user by ID")
  @Parameter(name = "id", description = "User UUID")
  @ApiResponse(responseCode = "200", description = "User found")
  @ApiResponse(responseCode = "404", description = "User not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.UUID) String id) {
    return userService.findOneById(id).toJson();
  }

  @GetMapping("/{id}/projects")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get projects for a user (self only)")
  @Parameter(name = "id", description = "User UUID")
  @ApiResponse(responseCode = "200", description = "List of user projects")
  @ApiResponse(responseCode = "403", description = "Can only view your own projects")
  @ApiResponse(responseCode = "404", description = "User not found")
  public ApiListResponse<Map<String, Object>> findUserProjects(
      @Param(value = "id", pipe = Param.Pipe.UUID) String id, @CurrentUser("id") String callerId) {
    return userService.findProjects(id, callerId);
  }
}
