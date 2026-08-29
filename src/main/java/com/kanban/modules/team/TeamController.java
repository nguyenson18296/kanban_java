package com.kanban.modules.team;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.team.dto.AddTeamMemberDto;
import com.kanban.modules.team.dto.CreateTeamDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project Teams")
@RestController
@RequestMapping("/projects/{projectId}/teams")
public class TeamController {
  private final TeamService teamService;

  public TeamController(TeamService teamService) {
    this.teamService = teamService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a team in a project (requires admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "201", description = "Team created")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project not found (also returned when the caller is not a project member)")
  @ApiResponse(responseCode = "409", description = "Team name already exists in project")
  public Map<String, Object> create(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @ValidatedBody CreateTeamDto dto, @CurrentUser("id") String userId) {
    return teamService.create(projectId, dto, userId).toJson();
  }

  @GetMapping
  @Operation(summary = "List all teams in a project")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "List of teams")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public ApiListResponse<Map<String, Object>> findAll(
      @Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId) {
    return teamService.findAllByProject(projectId);
  }

  @GetMapping("/{teamId}")
  @Operation(summary = "Get a team by ID")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "teamId", description = "Team ID")
  @ApiResponse(responseCode = "200", description = "Team found")
  @ApiResponse(responseCode = "404", description = "Team not found")
  public Map<String, Object> findOne(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "teamId", pipe = Param.Pipe.INT) int teamId) {
    return teamService.findOneById(projectId, teamId).toJson();
  }

  @GetMapping("/{teamId}/members")
  @Operation(summary = "List team members")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "teamId", description = "Team ID")
  @ApiResponse(responseCode = "200", description = "List of team members")
  @ApiResponse(responseCode = "404", description = "Team not found")
  public ApiListResponse<Map<String, Object>> getMembers(
      @Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "teamId", pipe = Param.Pipe.INT) int teamId) {
    return teamService.getMembers(projectId, teamId);
  }

  @PostMapping("/{teamId}/members")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Add a member to a team (requires admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "teamId", description = "Team ID")
  @ApiResponse(responseCode = "201", description = "Member added")
  @ApiResponse(responseCode = "400", description = "User is not a project member")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project or team not found (non-member callers get the project 404)")
  @ApiResponse(responseCode = "409", description = "User already in a team in this project")
  public void addMember(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "teamId", pipe = Param.Pipe.INT) int teamId, @ValidatedBody AddTeamMemberDto dto,
      @CurrentUser("id") String userId) {
    teamService.addMember(projectId, teamId, dto.user_id, userId);
  }

  @DeleteMapping("/{teamId}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove a member from a team (requires admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "teamId", description = "Team ID")
  @Parameter(name = "userId", description = "User UUID")
  @ApiResponse(responseCode = "204", description = "Member removed")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404",
      description = "Project or team not found (non-member callers get the project 404)")
  public void removeMember(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "teamId", pipe = Param.Pipe.INT) int teamId,
      @Param(value = "userId", pipe = Param.Pipe.UUID) String targetUserId,
      @CurrentUser("id") String actorId) {
    teamService.removeMember(projectId, teamId, targetUserId, actorId);
  }
}
