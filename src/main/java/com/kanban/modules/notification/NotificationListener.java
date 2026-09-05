package com.kanban.modules.notification;

import com.kanban.modules.notification.NotificationService.NewNotification;
import com.kanban.modules.notification.events.BaseNotificationEvent;
import com.kanban.modules.notification.events.CommentCreatedEvent;
import com.kanban.modules.notification.events.CommentMentionedEvent;
import com.kanban.modules.notification.events.ProjectInvitedEvent;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import com.kanban.modules.notification.events.TaskUpdatedEvent;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** Persists a notification row per recipient for each notification event. */
@Component
public class NotificationListener {
  private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);
  private final NotificationService notificationService;

  public NotificationListener(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Async("eventExecutor")
  @EventListener
  public void handleCommentCreated(CommentCreatedEvent event) {
    handle(event, NotificationType.COMMENT_CREATED, "comment.created");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleCommentMentioned(CommentMentionedEvent event) {
    handle(event, NotificationType.COMMENT_MENTIONED, "comment.mentioned");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleTaskAssigned(TaskAssignedEvent event) {
    handle(event, NotificationType.TASK_ASSIGNED, "task.assigned");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleTaskUpdated(TaskUpdatedEvent event) {
    handle(event, NotificationType.TASK_UPDATED, "task.updated");
  }

  @Async("eventExecutor")
  @EventListener
  public void handleProjectInvited(ProjectInvitedEvent event) {
    handle(event, NotificationType.PROJECT_INVITED, "project.invited");
  }

  private void handle(BaseNotificationEvent event, NotificationType type, String eventName) {
    try {
      List<NewNotification> notifications = new ArrayList<>();
      for (String recipientId : event.recipient_ids()) {
        notifications.add(new NewNotification(type, recipientId, event.actor_id(), event.entity_type(),
            event.entity_id(), event.payload()));
      }
      notificationService.createBatch(notifications);
    } catch (RuntimeException e) {
      log.error("Failed to handle {} event", eventName, e);
    }
  }
}
