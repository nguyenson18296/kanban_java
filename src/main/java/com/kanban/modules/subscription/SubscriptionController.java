package com.kanban.modules.subscription;

import com.kanban.common.pipes.Param;
import com.kanban.common.util.Dates;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.subscription.dto.SubscriberListResponseDto;
import com.kanban.modules.subscription.dto.SubscriptionStatusDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Task Subscriptions")
@RestController
@RequestMapping("/tasks")
public class SubscriptionController {
  private final SubscriptionService subscriptionService;

  public SubscriptionController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @PostMapping("/{taskId}/subscription")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Subscribe (watch) a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "201")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public SubscriptionStatusDto subscribe(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @CurrentUser("id") String userId) {
    subscriptionService.ensureTaskExists(taskId);
    subscriptionService.subscribeStrict(taskId, userId, SubscriptionSource.MANUAL);
    return subscriptionService.getMyStatus(taskId, userId);
  }

  @DeleteMapping("/{taskId}/subscription")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Unsubscribe from a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "204", description = "Unsubscribed")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public void unsubscribe(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @CurrentUser("id") String userId) {
    subscriptionService.ensureTaskExists(taskId);
    subscriptionService.unsubscribe(taskId, userId);
  }

  @GetMapping("/{taskId}/subscription/me")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get my subscription status for a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public SubscriptionStatusDto getMyStatus(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @CurrentUser("id") String userId) {
    subscriptionService.ensureTaskExists(taskId);
    return subscriptionService.getMyStatus(taskId, userId);
  }

  @GetMapping("/{taskId}/subscribers")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List all subscribers of a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "200")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public SubscriberListResponseDto listSubscribers(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId) {
    subscriptionService.ensureTaskExists(taskId);
    List<SubscriberListResponseDto.SubscriberDto> items = new ArrayList<>();
    for (TaskSubscription s : subscriptionService.listSubscribers(taskId)) {
      items.add(new SubscriberListResponseDto.SubscriberDto(
          s.getUserId(),
          s.getUser() == null || s.getUser().getFullName() == null ? "" : s.getUser().getFullName(),
          s.getUser() == null ? null : s.getUser().getAvatarUrl(),
          s.getSource(),
          Dates.iso(s.getCreatedAt())));
    }
    return new SubscriberListResponseDto(items);
  }
}
