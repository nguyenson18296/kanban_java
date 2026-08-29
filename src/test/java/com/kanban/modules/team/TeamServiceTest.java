package com.kanban.modules.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectMemberRepository;
import com.kanban.modules.project.ProjectRepository;
import com.kanban.modules.project.ProjectRole;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of team.service.spec.ts */
class TeamServiceTest {
  private TeamRepository teamRepository;
  private TeamMemberRepository teamMemberRepository;
  private ProjectRepository projectRepository;
  private ProjectMemberRepository projectMemberRepository;
  private ProjectAccessService projectAccessService;
  private TeamService service;

  private static NotFoundException maskedProject404() {
    return new NotFoundException(Json.map("statusCode", 404, "message", "Project with id \"proj1234\" not found"));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  @BeforeEach
  void setUp() {
    teamRepository = mock(TeamRepository.class);
    teamMemberRepository = mock(TeamMemberRepository.class);
    projectRepository = mock(ProjectRepository.class);
    projectMemberRepository = mock(ProjectMemberRepository.class);
    projectAccessService = mock(ProjectAccessService.class);
    service = new TeamService(teamRepository, teamMemberRepository, projectRepository, projectMemberRepository,
        projectAccessService);
  }

  @Nested
  class AddMember {
    @Test
    @DisplayName("masks team existence behind the project 404 for non-members")
    void masks() {
      when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(maskedProject404());
      assertThatThrownBy(() -> service.addMember("proj1234", 7, "target-user", "outsider"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"proj1234\" not found"));
      verify(teamRepository, never()).findByIdAndProjectId(anyInt(), any());
    }

    @Test
    @DisplayName("returns the team-flavored 404 to authorized actors when the team is missing")
    void teamMissing() {
      when(projectAccessService.ensureRole(any(), any(), any()))
          .thenReturn(new ProjectMember("proj1234", "admin-user", ProjectRole.ADMIN));
      when(teamRepository.findByIdAndProjectId(7, "proj1234")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.addMember("proj1234", 7, "target-user", "admin-user"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Team with id \"7\" not found in project \"proj1234\""));
    }

    @Test
    @DisplayName("adds the member once the gate and lookups pass")
    void adds() {
      when(projectAccessService.ensureRole(any(), any(), any()))
          .thenReturn(new ProjectMember("proj1234", "admin-user", ProjectRole.ADMIN));
      Team team = new Team();
      team.setId(7);
      when(teamRepository.findByIdAndProjectId(7, "proj1234")).thenReturn(Optional.of(team));
      when(projectMemberRepository.existsByProjectIdAndUserId("proj1234", "target-user")).thenReturn(true);
      when(teamMemberRepository.findByTeamIdAndUserId(7, "target-user")).thenReturn(Optional.empty());
      assertThatCode(() -> service.addMember("proj1234", 7, "target-user", "admin-user")).doesNotThrowAnyException();
      verify(teamMemberRepository).saveAndFlush(new TeamMember(7, "target-user", "proj1234"));
    }
  }

  @Nested
  class RemoveMember {
    @Test
    @DisplayName("masks team existence behind the project 404 for non-members")
    void masks() {
      when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(maskedProject404());
      assertThatThrownBy(() -> service.removeMember("proj1234", 7, "target-user", "outsider"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"proj1234\" not found"));
      verify(teamRepository, never()).findByIdAndProjectId(anyInt(), any());
    }

    @Test
    @DisplayName("returns the team-flavored 404 to authorized actors when the team is missing")
    void teamMissing() {
      when(projectAccessService.ensureRole(any(), any(), any()))
          .thenReturn(new ProjectMember("proj1234", "admin-user", ProjectRole.ADMIN));
      when(teamRepository.findByIdAndProjectId(7, "proj1234")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.removeMember("proj1234", 7, "target-user", "admin-user"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Team with id \"7\" not found in project \"proj1234\""));
    }

    @Test
    @DisplayName("deletes the membership once the gate and lookup pass")
    void deletes() {
      when(projectAccessService.ensureRole(any(), any(), any()))
          .thenReturn(new ProjectMember("proj1234", "admin-user", ProjectRole.ADMIN));
      Team team = new Team();
      team.setId(7);
      when(teamRepository.findByIdAndProjectId(7, "proj1234")).thenReturn(Optional.of(team));
      when(teamMemberRepository.deleteByTeamIdAndUserId(7, "target-user")).thenReturn(1);
      assertThatCode(() -> service.removeMember("proj1234", 7, "target-user", "admin-user"))
          .doesNotThrowAnyException();
      verify(teamMemberRepository).deleteByTeamIdAndUserId(7, "target-user");
    }
  }
}
