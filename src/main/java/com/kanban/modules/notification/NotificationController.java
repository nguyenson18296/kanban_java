package com.kanban.modules.notification;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.notification.dto.MarkNotificationsReadDto;
import com.kanban.modules.notification.dto.NotificationQueryDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@RestController
@RequestMapping("/notifications")
@JwtAuth
@SecurityRequirement(name = "bearer")
public class NotificationController {
  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @GetMapping
  @Operation(summary = "Get notifications for the current user (paginated)")
  @ApiResponse(responseCode = "200", description = "Paginated list of notifications")
  public PaginatedResponse<Map<String, Object>> findAll(@CurrentUser("id") String userId,
      @ValidatedQuery NotificationQueryDto query) {
    return notificationService.findByRecipient(userId, query);
  }

  @GetMapping("/unread-count")
  @Operation(summary = "Get unread notification count")
  @ApiResponse(responseCode = "200", description = "Unread count")
  public Map<String, Object> getUnreadCount(@CurrentUser("id") String userId) {
    return notificationService.getUnreadCount(userId);
  }

  @PatchMapping("/read")
  @Operation(summary = "Mark specific notifications as read")
  @ApiResponse(responseCode = "200", description = "Notifications marked as read")
  public Map<String, Object> markAsRead(@CurrentUser("id") String userId, @ValidatedBody MarkNotificationsReadDto dto) {
    return notificationService.markAsRead(userId, dto.ids);
  }

  @PatchMapping("/read-all")
  @Operation(summary = "Mark all notifications as read")
  @ApiResponse(responseCode = "200", description = "All notifications marked as read")
  public Map<String, Object> markAllAsRead(@CurrentUser("id") String userId) {
    return notificationService.markAllAsRead(userId);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a notification")
  @Parameter(name = "id", description = "Notification UUID")
  @ApiResponse(responseCode = "204", description = "Notification deleted")
  @ApiResponse(responseCode = "404", description = "Notification not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.UUID) String id, @CurrentUser("id") String userId) {
    notificationService.remove(id, userId);
  }
}
