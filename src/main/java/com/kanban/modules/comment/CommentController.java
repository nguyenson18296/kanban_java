package com.kanban.modules.comment;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.comment.dto.CommentQueryDto;
import com.kanban.modules.comment.dto.CreateCommentDto;
import com.kanban.modules.comment.dto.UpdateCommentDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Comments")
@RestController
public class CommentController {
  private final CommentService commentService;

  public CommentController(CommentService commentService) {
    this.commentService = commentService;
  }

  @PostMapping("/tasks/{taskId}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a comment on a task")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "201", description = "Comment created")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> create(@Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId,
      @CurrentUser("id") String userId, @ValidatedBody CreateCommentDto dto) {
    return commentService.create(taskId, userId, dto).toJson();
  }

  @GetMapping("/tasks/{taskId}/comments")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get comments for a task (paginated)")
  @Parameter(name = "taskId", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Paginated list of comments")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public PaginatedResponse<Map<String, Object>> findByTask(
      @Param(value = "taskId", pipe = Param.Pipe.UUID) String taskId, @ValidatedQuery CommentQueryDto query,
      @CurrentUser("id") String userId) {
    return commentService.findByTask(taskId, query, userId);
  }

  @PatchMapping("/comments/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Update a comment (owner only)")
  @Parameter(name = "id", description = "Comment UUID")
  @ApiResponse(responseCode = "200", description = "Comment updated")
  @ApiResponse(responseCode = "403", description = "Not the comment owner")
  @ApiResponse(responseCode = "404", description = "Comment not found")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @CurrentUser("id") String userId, @ValidatedBody UpdateCommentDto dto) {
    return commentService.update(id, userId, dto).toJson();
  }

  @DeleteMapping("/comments/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Delete a comment (owner only)")
  @Parameter(name = "id", description = "Comment UUID")
  @ApiResponse(responseCode = "204", description = "Comment deleted")
  @ApiResponse(responseCode = "403", description = "Not the comment owner")
  @ApiResponse(responseCode = "404", description = "Comment not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.UUID) String id, @CurrentUser("id") String userId) {
    commentService.remove(id, userId);
  }
}
