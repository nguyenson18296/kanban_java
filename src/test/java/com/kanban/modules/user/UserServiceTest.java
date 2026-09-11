package com.kanban.modules.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.project.Project;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectMemberRepository;
import com.kanban.modules.project.ProjectRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class UserServiceTest {
  private UserRepository userRepository;
  private ProjectMemberRepository projectMemberRepository;
  private UserService service;

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    projectMemberRepository = mock(ProjectMemberRepository.class);
    service = new UserService(userRepository, projectMemberRepository);
  }

  @Nested
  class FindProjects {
    @Test
    @DisplayName("403s when the caller asks for another user's projects, before any lookup")
    void forbidsOtherUsers() {
      assertThatThrownBy(() -> service.findProjects("u2", "u1"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "You can only view your own projects"));
      verify(userRepository, never()).findById(any());
      verify(projectMemberRepository, never()).findByUserIdWithProjectOrderByJoinedAtDesc(any());
    }

    @Test
    @DisplayName("404s when the user does not exist")
    void userMissing() {
      when(userRepository.findById("u1")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.findProjects("u1", "u1"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "User with id \"u1\" not found"));
    }

    @Test
    @DisplayName("returns each membership as the project plus role and joined_at for the caller")
    void listsOwnProjects() {
      when(userRepository.findById("u1"))
          .thenReturn(Optional.of(new User("u1", "a@b.co", "A", UserRole.BACKEND_DEVELOPER, null, true)));
      Project project = new Project();
      project.setId("proj1234");
      project.setName("Kanban");
      ProjectMember membership = new ProjectMember("proj1234", "u1", ProjectRole.ADMIN);
      membership.setProject(project);
      membership.setJoinedAt(Instant.parse("2026-07-04T00:00:00Z"));
      when(projectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc("u1"))
          .thenReturn(List.of(membership));

      ApiListResponse<Map<String, Object>> res = service.findProjects("u1", "u1");

      assertThat(res.success()).isTrue();
      assertThat(res.data()).hasSize(1);
      assertThat(res.data().get(0))
          .containsEntry("id", "proj1234")
          .containsEntry("name", "Kanban")
          .containsEntry("role", ProjectRole.ADMIN)
          .containsEntry("joined_at", Instant.parse("2026-07-04T00:00:00Z"))
          .containsKey("creator");
    }
  }
}
