package com.kanban.modules.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.team.TeamMemberRepository;
import com.kanban.modules.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Port of project.service.spec.ts — member management. */
class ProjectServiceTest {
  private ProjectRepository projectRepository;
  private ProjectMemberRepository memberRepository;
  private UserRepository userRepository;
  private TeamMemberRepository teamMemberRepository;
  private TransactionTemplate transactionTemplate;
  private ProjectAccessService projectAccessService;
  private ProjectService service;

  private static ProjectMember member(String userId, ProjectRole role) {
    return new ProjectMember("proj1234", userId, role);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    memberRepository = mock(ProjectMemberRepository.class);
    userRepository = mock(UserRepository.class);
    teamMemberRepository = mock(TeamMemberRepository.class);
    transactionTemplate = mock(TransactionTemplate.class);
    projectAccessService = mock(ProjectAccessService.class);
    service = new ProjectService(projectRepository, memberRepository, userRepository, teamMemberRepository,
        transactionTemplate, projectAccessService);
  }

  @Nested
  class ChangeMemberRole {
    @BeforeEach
    void lockableProject() {
      // changeMemberRole serializes per project by locking the project row first
      when(projectRepository.findByIdForUpdate("proj1234")).thenReturn(Optional.of(new Project()));
    }

    @Test
    @DisplayName("404s (masked) when the project does not exist, before any membership read")
    void notFoundWhenProjectMissing() {
      when(projectRepository.findByIdForUpdate("ghost123")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.changeMemberRole("ghost123", "target", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"ghost123\" not found"));
      verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects changing your own role")
    void rejectsSelfChange() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "actor", ProjectRole.ADMIN, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "You cannot change your own role"));
    }

    @Test
    @DisplayName("404s when the target is not a member")
    void notFoundWhenTargetNotMember() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "ghost")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "ghost", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "User is not a member of this project"));
    }

    @Test
    @DisplayName("lets an admin toggle member <-> viewer")
    void adminTogglesMemberViewer() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.VIEWER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.VIEWER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.VIEWER);
      verify(memberRepository).save(any(ProjectMember.class));
    }

    @Test
    @DisplayName("blocks an admin from promoting to admin (owner-only)")
    void blocksAdminPromotingToAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.ADMIN, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
      verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("blocks an admin from demoting an owner")
    void blocksAdminDemotingOwner() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
    }

    @Test
    @DisplayName("409s when demoting the last owner")
    void conflictsOnLastOwnerDemotion() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));
      when(memberRepository.countByProjectIdAndRole("proj1234", ProjectRole.OWNER)).thenReturn(1L);

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A project must have at least one owner"));
    }

    @Test
    @DisplayName("lets an owner promote another member to owner")
    void ownerPromotesMemberToOwner() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.OWNER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    @DisplayName("same-role change is a no-op that still returns the membership")
    void sameRoleIsNoOp() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.MEMBER);
      verify(memberRepository, never()).save(any());
    }
  }
  @Nested
  class RemoveMembers {
    @BeforeEach
    void lockableProject() {
      // removeMembers serializes per project by locking the project row first
      when(projectRepository.findByIdForUpdate("proj1234")).thenReturn(Optional.of(new Project()));
    }

    @Test
    @DisplayName("404s (masked) when the project does not exist, before any membership delete")
    void notFoundWhenProjectMissing() {
      when(projectRepository.findByIdForUpdate("ghost123")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.removeMembers("ghost123", List.of("target"), "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"ghost123\" not found"));
      verify(memberRepository, never()).deleteByProjectIdAndUserIdIn("ghost123", List.of("target"));
    }

    @Test
    @DisplayName("allows a viewer to remove themselves (self-leave)")
    void allowsSelfLeave() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.VIEWER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("actor")))
          .thenReturn(List.of(member("actor", ProjectRole.VIEWER)));

      assertThatCode(() -> service.removeMembers("proj1234", List.of("actor"), "actor"))
          .doesNotThrowAnyException();

      verify(teamMemberRepository).deleteByProjectIdAndUserIdIn("proj1234", List.of("actor"));
      verify(memberRepository).deleteByProjectIdAndUserIdIn("proj1234", List.of("actor"));
    }

    @Test
    @DisplayName("blocks a member from removing someone else")
    void blocksMemberRemovingSomeoneElse() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.MEMBER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.MEMBER)));

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least admin role"));
    }

    @Test
    @DisplayName("blocks an admin from removing another admin (owner-only)")
    void blocksAdminRemovingAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.ADMIN)));

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
    }

    @Test
    @DisplayName("409s when removal would leave zero owners (including self-leave)")
    void conflictsOnLastOwnerRemoval() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("actor")))
          .thenReturn(List.of(member("actor", ProjectRole.OWNER)));
      when(memberRepository.countByProjectIdAndRole("proj1234", ProjectRole.OWNER)).thenReturn(1L);

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("actor"), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A project must have at least one owner"));
    }

    @Test
    @DisplayName("lets an owner remove an admin")
    void ownerRemovesAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.ADMIN)));

      assertThatCode(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .doesNotThrowAnyException();
    }
  }
}
