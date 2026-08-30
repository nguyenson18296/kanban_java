package com.kanban.modules.project.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.common.json.Json;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Port of project-role.guard.spec.ts. The {@code PROJECT_ROLE_KEY} metadata-key
 * test is skipped: in Java the {@code @RequireProjectRole} annotation itself is
 * the metadata, there is no separate reflector key to assert on.
 */
class ProjectRoleInterceptorTest {

  private ProjectAccessService projectAccessService;
  private ProjectRoleInterceptor interceptor;
  private MockHttpServletResponse response;

  /** Dummy handler methods carrying (or not carrying) the annotation under test. */
  private static class DummyController {
    @RequireProjectRole(ProjectRole.VIEWER)
    void withDefaultParam() {}

    @RequireProjectRole(value = ProjectRole.ADMIN, param = "id")
    void withCustomParam() {}

    void withoutAnnotation() {}
  }

  /** Dummy controller declaring the annotation at class level. */
  @RequireProjectRole(ProjectRole.MEMBER)
  private static class AnnotatedController {
    void inheritsClassRole() {}

    @RequireProjectRole(value = ProjectRole.OWNER, param = "id")
    void overridesClassRole() {}
  }

  private static HandlerMethod handlerMethod(String name) throws NoSuchMethodException {
    Method method = DummyController.class.getDeclaredMethod(name);
    return new HandlerMethod(new DummyController(), method);
  }

  private static HandlerMethod annotatedHandlerMethod(String name) throws NoSuchMethodException {
    Method method = AnnotatedController.class.getDeclaredMethod(name);
    return new HandlerMethod(new AnnotatedController(), method);
  }

  private static User userWithId(String id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  @BeforeEach
  void setUp() {
    projectAccessService = mock(ProjectAccessService.class);
    interceptor = new ProjectRoleInterceptor(projectAccessService);
    response = new MockHttpServletResponse();
  }

  @Test
  @DisplayName("passes routes with no @RequireProjectRole metadata untouched")
  void passesUnannotatedRoutes() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();

    boolean result = interceptor.preHandle(request, response, handlerMethod("withoutAnnotation"));

    assertThat(result).isTrue();
    verifyNoInteractions(projectAccessService);
  }

  @Test
  @DisplayName("throws UnauthorizedException when request has no authenticated user")
  void throwsWhenUserMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj1234"));

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  @DisplayName("throws NotFoundException when the named path variable is missing")
  void throwsWhenPathVariableMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    // no URI_TEMPLATE_VARIABLES_ATTRIBUTE set at all.

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isInstanceOf(NotFoundException.class)
        .satisfies(e -> assertThat(((HttpException) e).getResponse())
            .isEqualTo(Map.of("statusCode", 404, "message", "Project not found")));
  }

  @Test
  @DisplayName("enforces the role from the named route param and attaches the membership")
  void enforcesRoleAndAttachesMembership() throws Exception {
    ProjectMember membership = new ProjectMember("proj1234", "user-1", ProjectRole.OWNER);
    when(projectAccessService.ensureRole("proj1234", "user-1", ProjectRole.ADMIN)).thenReturn(membership);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "proj1234"));

    boolean result = interceptor.preHandle(request, response, handlerMethod("withCustomParam"));

    assertThat(result).isTrue();
    verify(projectAccessService).ensureRole("proj1234", "user-1", ProjectRole.ADMIN);
    assertThat(request.getAttribute(ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE)).isSameAs(membership);
  }

  @Test
  @DisplayName("falls back to the controller-level @RequireProjectRole")
  void enforcesClassLevelRole() throws Exception {
    ProjectMember membership = new ProjectMember("proj1234", "user-1", ProjectRole.MEMBER);
    when(projectAccessService.ensureRole("proj1234", "user-1", ProjectRole.MEMBER)).thenReturn(membership);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj1234"));

    boolean result = interceptor.preHandle(request, response, annotatedHandlerMethod("inheritsClassRole"));

    assertThat(result).isTrue();
    verify(projectAccessService).ensureRole("proj1234", "user-1", ProjectRole.MEMBER);
    assertThat(request.getAttribute(ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE)).isSameAs(membership);
  }

  @Test
  @DisplayName("method-level @RequireProjectRole overrides the controller-level one")
  void methodLevelOverridesClassLevel() throws Exception {
    ProjectMember membership = new ProjectMember("proj1234", "user-1", ProjectRole.OWNER);
    when(projectAccessService.ensureRole("proj1234", "user-1", ProjectRole.OWNER)).thenReturn(membership);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "proj1234"));

    boolean result = interceptor.preHandle(request, response, annotatedHandlerMethod("overridesClassRole"));

    assertThat(result).isTrue();
    verify(projectAccessService).ensureRole("proj1234", "user-1", ProjectRole.OWNER);
    assertThat(request.getAttribute(ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE)).isSameAs(membership);
  }

  @Test
  @DisplayName("propagates the masked 404 ProjectAccessService throws for a non-member untouched")
  void propagatesGateNotFound() throws Exception {
    NotFoundException masked = new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Project with id \"proj1234\" not found"));
    when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(masked);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj1234"));

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isSameAs(masked);
    assertThat(request.getAttribute(ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE)).isNull();
  }

  @Test
  @DisplayName("propagates the 403 ProjectAccessService throws below the required role untouched")
  void propagatesGateForbidden() throws Exception {
    ForbiddenException denied = new ForbiddenException(Json.map(
        "statusCode", 403,
        "message", "This action requires at least viewer role"));
    when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(denied);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj1234"));

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isSameAs(denied);
    assertThat(request.getAttribute(ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE)).isNull();
  }
}
