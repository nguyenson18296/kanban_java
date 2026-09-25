package com.kanban.modules.dependency;

import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.dependency.dto.ManageDependenciesDto;
import com.kanban.modules.dependency.dto.TaskDependenciesResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Task Dependencies")
@RestController
@RequestMapping("/tasks")
public class DependencyController {
  private final DependencyService dependencyService;

  public DependencyController(DependencyService dependencyService) {
    this.dependencyService = dependencyService;
  }

  @PostMapping("/{id}/dependencies")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Declare that this task is blocked by one or more other tasks")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "201", description = "Both dependency directions after the change")
  @ApiResponse(responseCode = "403", description = "Requires at least member role")
  @ApiResponse(responseCode = "404", description = "Task not found, or a blocker is not a task in this project")
  @ApiResponse(responseCode = "409", description = "Would create a dependency cycle, or blocks itself")
  public TaskDependenciesResponseDto add(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageDependenciesDto dto, @CurrentUser("id") String userId) {
    return dependencyService.add(id, dto.blocked_by_ids, userId);
  }

  @DeleteMapping("/{id}/dependencies")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove dependency links from this task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "204", description = "Removed; blocker ids matching no existing link are ignored")
  @ApiResponse(responseCode = "403", description = "Requires at least member role")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageDependenciesDto dto, @CurrentUser("id") String userId) {
    dependencyService.remove(id, dto.blocked_by_ids, userId);
  }

  @GetMapping("/{id}/dependencies")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List both dependency directions for this task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "What blocks this task, and what it blocks")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public TaskDependenciesResponseDto list(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @CurrentUser("id") String userId) {
    return dependencyService.list(id, userId);
  }
}
