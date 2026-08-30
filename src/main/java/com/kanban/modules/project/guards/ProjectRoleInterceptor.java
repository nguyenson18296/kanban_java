package com.kanban.modules.project.guards;

import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.common.json.Json;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Port of ProjectRoleGuard: enforces the minimum project role declared by
 * {@code @RequireProjectRole} on the handler method, falling back to the one on
 * the controller class when the method has none. Must be registered after
 * {@code JwtAuthInterceptor} in {@code WebMvcConfig} so the authenticated user is
 * already attached to the request.
 */
@Component
public class ProjectRoleInterceptor implements HandlerInterceptor {
  public static final String MEMBERSHIP_ATTRIBUTE = "projectMembership";

  private final ProjectAccessService projectAccessService;

  public ProjectRoleInterceptor(ProjectAccessService projectAccessService) {
    this.projectAccessService = projectAccessService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RequireProjectRole meta = method.getMethodAnnotation(RequireProjectRole.class);
    if (meta == null) {
      meta = method.getBeanType().getAnnotation(RequireProjectRole.class);
    }
    if (meta == null) {
      return true;
    }
    User user = (User) request.getAttribute(JwtAuthInterceptor.USER_ATTRIBUTE);
    if (user == null) {
      // JwtAuthInterceptor must be registered before this interceptor.
      throw new UnauthorizedException();
    }
    String projectId = pathVariable(request, meta.param());
    if (projectId == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Project not found"));
    }
    request.setAttribute(
        MEMBERSHIP_ATTRIBUTE,
        projectAccessService.ensureRole(projectId, user.getId(), meta.value()));
    return true;
  }

  private static String pathVariable(HttpServletRequest request, String name) {
    Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(vars instanceof Map<?, ?> map)) {
      return null;
    }
    Object value = map.get(name);
    return value == null ? null : value.toString();
  }
}
