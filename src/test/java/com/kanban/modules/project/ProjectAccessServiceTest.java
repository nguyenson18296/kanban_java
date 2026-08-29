package com.kanban.modules.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Port of project-access.service.spec.ts */
class ProjectAccessServiceTest {
  private ProjectMemberRepository memberRepository;
  private ProjectAccessQueries queries;
  private ProjectAccessService service;

  private static ProjectMember membership(ProjectRole role) {
    return new ProjectMember("proj1234", "user-1", role);
  }

  private static Map<String, Object> response(HttpException e) {
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) e.getResponse();
    return body;
  }

  @BeforeEach
  void setUp() {
    memberRepository = mock(ProjectMemberRepository.class);
    queries = mock(ProjectAccessQueries.class);
    service = new ProjectAccessService(memberRepository, queries);
  }

  @Nested
  class EnsureRole {
    @Test
    @DisplayName("throws NotFoundException (masking) when the user is not a member")
    void masksNonMembers() {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"proj1234\" not found"));
    }

    @Test
    @DisplayName("throws ForbiddenException when the member is below the required role")
    void forbidsBelowRole() {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }

    @ParameterizedTest(name = "allows {0} when {1} is required")
    @CsvSource({"VIEWER,VIEWER", "MEMBER,MEMBER", "ADMIN,MEMBER", "OWNER,ADMIN", "OWNER,OWNER"})
    void allows(ProjectRole has, ProjectRole needs) {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.of(membership(has)));
      assertThat(service.ensureRole("proj1234", "user-1", needs).getRole()).isEqualTo(has);
    }

    @ParameterizedTest(name = "rejects {0} when {1} is required")
    @CsvSource({"MEMBER,ADMIN", "ADMIN,OWNER", "VIEWER,OWNER"})
    void rejects(ProjectRole has, ProjectRole needs) {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.of(membership(has)));
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", needs)).isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  class GetProjectIdForTask {
    @Test
    @DisplayName("returns the project id resolved through the task column")
    void resolves() {
      when(queries.findProjectIdForTask("task-uuid")).thenReturn(Optional.of("proj1234"));
      assertThat(service.getProjectIdForTask("task-uuid")).isEqualTo("proj1234");
      verify(queries).findProjectIdForTask("task-uuid");
    }

    @Test
    @DisplayName("throws NotFoundException when the task does not exist")
    void notFound() {
      when(queries.findProjectIdForTask("missing")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.getProjectIdForTask("missing"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Task with id \"missing\" not found"));
    }
  }

  @Test
  @DisplayName("getProjectIdsForUser returns the project ids of all memberships")
  void getProjectIdsForUser() {
    when(memberRepository.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1", "p2"));
    assertThat(service.getProjectIdsForUser("user-1")).containsExactly("p1", "p2");
  }

  @Nested
  class GetProjectIdForColumn {
    @Test
    @DisplayName("returns the project id resolved through the column")
    void resolves() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      assertThat(service.getProjectIdForColumn(7)).isEqualTo("proj1234");
      verify(queries).findProjectIdForColumn(7);
    }

    @Test
    @DisplayName("throws NotFoundException when the column does not exist")
    void notFound() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.getProjectIdForColumn(7))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Column with id \"7\" not found"));
    }
  }

  @Nested
  class EnsureTaskRole {
    @Test
    @DisplayName("resolves the project then enforces the role, returning the project id")
    void resolves() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.MEMBER)));
      assertThat(service.ensureTaskRole("task-uuid", "user-1", ProjectRole.MEMBER)).isEqualTo("proj1234");
    }

    @Test
    @DisplayName("masks non-membership as a task-not-found 404 (never the project-flavored message)")
    void masks() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureTaskRole("task-uuid", "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Task with id \"task-uuid\" not found"));
    }

    @Test
    @DisplayName("still throws ForbiddenException for a member below the required role")
    void forbids() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any()))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureTaskRole("task-uuid", "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }
  }

  @Nested
  class EnsureColumnRole {
    @Test
    @DisplayName("masks non-membership as a column-not-found 404 (never the project-flavored message)")
    void masks() {
      when(queries.findProjectIdForColumn(anyInt())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureColumnRole(7, "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Column with id \"7\" not found"));
    }

    @Test
    @DisplayName("resolves the project then enforces the role, returning the project id")
    void resolves() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.MEMBER)));
      assertThat(service.ensureColumnRole(7, "user-1", ProjectRole.MEMBER)).isEqualTo("proj1234");
    }

    @Test
    @DisplayName("still throws ForbiddenException for a member below the required role")
    void forbids() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any()))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureColumnRole(7, "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }
  }
}
