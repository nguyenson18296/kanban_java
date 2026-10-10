package com.kanban.modules.dashboard;

import com.kanban.common.pipes.Param;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.project.guards.RequireProjectRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Singular {@code /dashboard}: one statistics sub-resource per project (JSP-44), not a collection. */
@Tag(name = "Project Dashboard")
@RestController
@RequestMapping("/projects/{projectId}/dashboard")
public class DashboardController {
  private final DashboardService dashboardService;

  public DashboardController(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  @GetMapping
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @RequireProjectRole(value = ProjectRole.VIEWER)
  @Operation(summary = "Task statistics for a project (any project member)",
      description = "Counts every task and subtask in the project's non-archived columns: total, overdue, "
          + "per status, per assignee and unassigned. The numbers may be up to the cache TTL old; "
          + "computed_at says when they were computed.")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "Dashboard statistics")
  @ApiResponse(responseCode = "401", description = "Missing or invalid token")
  @ApiResponse(responseCode = "404",
      description = "Project not found (also returned when the caller is not a project member)")
  public DashboardResponse get(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId) {
    return dashboardService.get(projectId);
  }
}
