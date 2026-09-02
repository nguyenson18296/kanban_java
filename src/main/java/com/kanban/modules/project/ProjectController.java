package com.kanban.modules.project;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.project.dto.CreateProjectDto;
import com.kanban.modules.project.dto.ManageProjectMembersDto;
import com.kanban.modules.project.dto.UpdateMemberRoleDto;
import com.kanban.modules.project.dto.UpdateProjectDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Projects")
@RestController
@RequestMapping("/projects")
public class ProjectController {
  private final ProjectService projectService;

  public ProjectController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a project")
  @ApiResponse(responseCode = "201", description = "Project created")
  @ApiResponse(responseCode = "409", description = "Project name already exists")
  public Map<String, Object> create(@ValidatedBody CreateProjectDto dto, @CurrentUser("id") String userId) {
    return projectService.create(dto, userId).toJson(true);
  }

  @GetMapping
  @Operation(summary = "Get all projects")
  @ApiResponse(responseCode = "200", description = "List of projects")
  public List<Map<String, Object>> findAll() {
    return projectService.findAll().stream().map(p -> p.toJson(true)).toList();
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a project by ID")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "Project found")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id) {
    return projectService.findOneById(id).toJson(true);
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update a project")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "Project updated")
  @ApiResponse(responseCode = "404", description = "Project not found")
  @ApiResponse(responseCode = "409", description = "Project name already exists")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id,
      @ValidatedBody UpdateProjectDto dto) {
    return projectService.update(id, dto).toJson(true);
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a project")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "Project deleted")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id) {
    projectService.remove(id);
  }

  // --- Project Members ---

  @GetMapping("/{id}/members")
  @Operation(summary = "Get project members")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "List of project members")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public ApiListResponse<Map<String, Object>> getMembers(
      @Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id) {
    return projectService.getMembers(id);
  }

  @PostMapping("/{id}/members")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Add members to a project (requires admin+)")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "201", description = "Members added")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project or user not found (also returned when the caller is not a project member)")
  public void addMembers(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id,
      @ValidatedBody ManageProjectMembersDto dto, @CurrentUser("id") String userId) {
    projectService.addMembers(id, dto.user_ids, userId);
  }

  @DeleteMapping("/{id}/members")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove members from a project (admin+; owner for admin/owner targets; self-leave at any role)")
  @Parameter(name = "id", description = "Project ID")
  @ApiResponse(responseCode = "204", description = "Members removed")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project not found (also returned when the caller is not a project member)")
  @ApiResponse(responseCode = "409", description = "Project must keep at least one owner")
  public void removeMembers(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id,
      @ValidatedBody ManageProjectMembersDto dto, @CurrentUser("id") String userId) {
    projectService.removeMembers(id, dto.user_ids, userId);
  }

  @PatchMapping("/{id}/members/{userId}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Change a member's role (admin+; owner for owner/admin changes)")
  @Parameter(name = "id", description = "Project ID")
  @Parameter(name = "userId", description = "User UUID")
  @ApiResponse(responseCode = "200", description = "Member role updated")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project or member not found (also returned when the caller is not a project member)")
  @ApiResponse(responseCode = "409", description = "Project must keep at least one owner")
  public Map<String, Object> changeMemberRole(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id,
      @Param(value = "userId", pipe = Param.Pipe.UUID) String userId,
      @ValidatedBody UpdateMemberRoleDto dto, @CurrentUser("id") String actorId) {
    return projectService.changeMemberRole(id, userId, dto.role, actorId).toJsonWithUser();
  }
}
