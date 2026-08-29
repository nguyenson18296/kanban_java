package com.kanban.modules.comment;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.common.events.EventBus;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.comment.dto.CommentQueryDto;
import com.kanban.modules.comment.dto.CommentSortOrder;
import com.kanban.modules.comment.dto.CreateCommentDto;
import com.kanban.modules.comment.dto.UpdateCommentDto;
import com.kanban.modules.mention.MentionService;
import com.kanban.modules.notification.events.CommentCreatedEvent;
import com.kanban.modules.notification.events.CommentMentionedEvent;
import com.kanban.modules.notification.events.NotificationEvents;
import com.kanban.modules.subscription.SubscriptionService;
import com.kanban.modules.subscription.SubscriptionSource;
import com.kanban.modules.task.Task;
import com.kanban.modules.task.TaskRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class CommentService {
  private static final Logger log = LoggerFactory.getLogger(CommentService.class);

  private final CommentRepository commentRepository;
  private final TaskRepository taskRepository;
  private final EventBus eventBus;
  private final SubscriptionService subscriptionService;
  private final MentionService mentionService;

  public CommentService(CommentRepository commentRepository, TaskRepository taskRepository, EventBus eventBus,
      SubscriptionService subscriptionService, MentionService mentionService) {
    this.commentRepository = commentRepository;
    this.taskRepository = taskRepository;
    this.eventBus = eventBus;
    this.subscriptionService = subscriptionService;
    this.mentionService = mentionService;
  }

  public Comment create(String taskId, String authorId, CreateCommentDto dto) {
    try {
      Task task = taskRepository.findById(taskId).orElseThrow(() -> taskNotFound(taskId));
      Comment comment = new Comment();
      comment.setContent(dto.content);
      comment.setTaskId(taskId);
      comment.setAuthorId(authorId);
      Comment saved = commentRepository.save(comment);
      Comment result = findOneById(saved.getId());
      String stripped = dto.content.replaceAll("<[^>]*>", "");
      String preview = stripped.substring(0, Math.min(120, stripped.length()));

      // KAN-78: the commenter is auto-subscribed to the task.
      subscriptionService.subscribe(taskId, authorId, SubscriptionSource.COMMENTED);

      // Resolve @mentioned users (exclude only the author). Runs AFTER the comment
      // is persisted, so a lookup failure must never fail the request.
      List<String> mentionedUserIds = List.of();
      try {
        mentionedUserIds = mentionService.resolveMentionedUserIds(dto.content, List.of(authorId));
        if (!mentionedUserIds.isEmpty()) {
          subscriptionService.subscribeMany(taskId, mentionedUserIds, SubscriptionSource.MENTIONED);
        }
      } catch (RuntimeException e) {
        log.error("Failed to resolve or subscribe mentions for task {}", taskId, e);
        mentionedUserIds = List.of();
      }

      // Fan out COMMENT_CREATED to subscribers, minus the author and anyone
      // already receiving a COMMENT_MENTIONED for this comment (dedupe).
      List<String> subscriberIds = subscriptionService.getSubscriberIds(taskId);
      Set<String> mentionedSet = new HashSet<>(mentionedUserIds);
      List<String> commentRecipients = subscriberIds.stream()
          .filter(id -> !id.equals(authorId) && !mentionedSet.contains(id)).toList();
      if (!commentRecipients.isEmpty()) {
        eventBus.emit(NotificationEvents.COMMENT_CREATED, new CommentCreatedEvent(authorId, taskId, commentRecipients,
            Json.map(
                "task_id", taskId,
                "task_title", task.getTitle(),
                "ticket_id", task.getTicketId(),
                "comment_id", saved.getId(),
                "comment_preview", preview,
                "author", Json.map(
                    "id", result.getAuthor().getId(),
                    "full_name", result.getAuthor().getFullName(),
                    "avatar_url", result.getAuthor().getAvatarUrl()))));
      }
      if (!mentionedUserIds.isEmpty()) {
        eventBus.emit(NotificationEvents.COMMENT_MENTIONED, new CommentMentionedEvent(authorId, saved.getId(),
            mentionedUserIds, Json.map(
                "task_id", taskId,
                "task_title", task.getTitle(),
                "ticket_id", task.getTicketId(),
                "comment_id", saved.getId(),
                "comment_preview", preview)));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to create comment", e);
      throw internal("Failed to create comment", e);
    }
  }

  public PaginatedResponse<Map<String, Object>> findByTask(String taskId, CommentQueryDto query) {
    try {
      ensureTaskExists(taskId);
      int page = query.page == null ? 1 : query.page;
      int limit = query.limit == null ? 20 : query.limit;
      CommentSortOrder sort = query.sort == null ? CommentSortOrder.DESC : query.sort;
      Sort.Direction direction = sort == CommentSortOrder.ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
      Page<Comment> result = commentRepository.findByTaskIdWithAuthor(taskId,
          PageRequest.of(page - 1, limit, Sort.by(direction, "createdAt")));
      return new PaginatedResponse<>(result.getContent().stream().map(Comment::toJson).toList(),
          PaginationMeta.of(page, limit, result.getTotalElements()));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch comments", e);
      throw internal("Failed to fetch comments", e);
    }
  }

  public Comment update(String commentId, String userId, UpdateCommentDto dto) {
    try {
      Comment comment = findOneById(commentId);
      ensureOwnership(comment, userId);
      comment.setContent(dto.content);
      comment.setEdited(true);
      commentRepository.save(comment);
      return findOneById(commentId);
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to update comment", e);
      throw internal("Failed to update comment", e);
    }
  }

  public void remove(String commentId, String userId) {
    try {
      Comment comment = findOneById(commentId);
      ensureOwnership(comment, userId);
      commentRepository.deleteById(comment.getId());
    } catch (NotFoundException | ForbiddenException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to delete comment", e);
      throw internal("Failed to delete comment", e);
    }
  }

  private Comment findOneById(String id) {
    return commentRepository.findByIdWithAuthor(id).orElseThrow(() -> new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Comment with id \"" + id + "\" not found")));
  }

  private void ensureTaskExists(String taskId) {
    if (!taskRepository.existsById(taskId)) {
      throw taskNotFound(taskId);
    }
  }

  private static void ensureOwnership(Comment comment, String userId) {
    if (!userId.equals(comment.getAuthorId())) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "You can only modify your own comments"));
    }
  }

  private static NotFoundException taskNotFound(String taskId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + taskId + "\" not found"));
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
