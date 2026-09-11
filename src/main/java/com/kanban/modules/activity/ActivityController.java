package com.kanban.modules.activity;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.activity.dto.ActivityQueryDto;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Task Activities")
@RestController
@RequestMapping("/tasks")
public class ActivityController {
  private final ActivityService activityService;

  public ActivityController(ActivityService activityService) {
    this.activityService = activityService;
  }

  @GetMapping("/{taskId}/activities")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get activity history for a task (paginated)")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Paginated list of task activities")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public PaginatedResponse<Map<String, Object>> findByTask(
      @Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId, @ValidatedQuery ActivityQueryDto query,
      @CurrentUser("id") String userId) {
    return activityService.findByTask(taskId, query, userId);
  }
}
