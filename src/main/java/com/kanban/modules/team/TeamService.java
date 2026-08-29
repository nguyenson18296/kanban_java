package com.kanban.modules.team;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.BadRequestException;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMemberRepository;
import com.kanban.modules.project.ProjectRepository;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.team.dto.CreateTeamDto;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TeamService {
  private static final Logger log = LoggerFactory.getLogger(TeamService.class);

  private final TeamRepository teamRepository;
  private final TeamMemberRepository teamMemberRepository;
  private final ProjectRepository projectRepository;
  private final ProjectMemberRepository projectMemberRepository;
  private final ProjectAccessService projectAccessService;

  public TeamService(TeamRepository teamRepository, TeamMemberRepository teamMemberRepository,
      ProjectRepository projectRepository, ProjectMemberRepository projectMemberRepository,
      ProjectAccessService projectAccessService) {
    this.teamRepository = teamRepository;
    this.teamMemberRepository = teamMemberRepository;
    this.projectRepository = projectRepository;
    this.projectMemberRepository = projectMemberRepository;
    this.projectAccessService = projectAccessService;
  }

  public Team create(String projectId, CreateTeamDto dto, String actorId) {
    try {
      ensureProjectExists(projectId);
      projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
      Team team = new Team();
      team.setName(dto.name);
      if (dto.has("description")) {
        team.setDescription(dto.description);
      }
      if (dto.has("color")) {
        team.setColor(dto.color);
      }
      team.setProjectId(projectId);
      Team saved = teamRepository.saveAndFlush(team);
      return findOneById(projectId, saved.getId());
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Team \"" + dto.name + "\" already exists in this project"));
      }
      log.error("Failed to create team", error);
      throw internal("Failed to create team", error);
    }
  }

  public ApiListResponse<Map<String, Object>> findAllByProject(String projectId) {
    try {
      ensureProjectExists(projectId);
      return ApiListResponse.ok(teamRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
          .map(Team::toJson).toList());
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch teams", e);
      throw internal("Failed to fetch teams", e);
    }
  }

  public Team findOneById(String projectId, int teamId) {
    try {
      return teamRepository.findByIdAndProjectId(teamId, projectId).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Team with id \"" + teamId + "\" not found in project \"" + projectId + "\"")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch team", e);
      throw internal("Failed to fetch team", e);
    }
  }

  public ApiListResponse<Map<String, Object>> getMembers(String projectId, int teamId) {
    try {
      findOneById(projectId, teamId);
      return ApiListResponse.ok(teamMemberRepository
          .findByTeamIdAndProjectIdWithUserOrderByJoinedAtAsc(teamId, projectId).stream()
          .map(TeamMember::toJsonWithUser).toList());
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch team members", e);
      throw internal("Failed to fetch team members", e);
    }
  }

  public void addMember(String projectId, int teamId, String userId, String actorId) {
    try {
      // Gate first: a non-member must get the masked project 404 before any
      // team lookup can reveal whether the team exists (anti-enumeration).
      projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
      findOneById(projectId, teamId);
      boolean isProjectMember = projectMemberRepository.existsByProjectIdAndUserId(projectId, userId);
      if (!isProjectMember) {
        throw new BadRequestException(Json.map(
            "statusCode", 400,
            "message", "User \"" + userId + "\" is not a member of project \"" + projectId + "\""));
      }
      if (teamMemberRepository.findByTeamIdAndUserId(teamId, userId).isPresent()) {
        return;
      }
      teamMemberRepository.saveAndFlush(new TeamMember(teamId, userId, projectId));
    } catch (NotFoundException | BadRequestException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException error) {
      String constraint = PgErrors.constraint(error);
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION) && constraint != null && constraint.contains("user_id")) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "User is already assigned to a team in this project"));
      }
      log.error("Failed to add team member", error);
      throw internal("Failed to add team member", error);
    }
  }

  public void removeMember(String projectId, int teamId, String userId, String actorId) {
    try {
      // Gate first: see addMember — the masked 404 must win for non-members.
      projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
      findOneById(projectId, teamId);
      teamMemberRepository.deleteByTeamIdAndUserId(teamId, userId);
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException error) {
      log.error("Failed to remove team member", error);
      throw internal("Failed to remove team member", error);
    }
  }

  private void ensureProjectExists(String id) {
    if (!projectRepository.existsById(id)) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Project with id \"" + id + "\" not found"));
    }
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
