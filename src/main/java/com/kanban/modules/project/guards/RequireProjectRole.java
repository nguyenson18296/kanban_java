package com.kanban.modules.project.guards;

import com.kanban.modules.project.ProjectRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum project role required for a route. The project id is read
 * from the path variable named {@link #param()} (default {@code "projectId"};
 * pass {@code "id"} when the controller uses {@code /{id}}). May be placed on a
 * controller class to cover every route it serves; a method-level annotation
 * overrides the class-level one. Enforced by {@link ProjectRoleInterceptor},
 * which must run after the JWT auth interceptor so the authenticated user is
 * already attached to the request.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequireProjectRole {
  ProjectRole value();

  String param() default "projectId";
}
