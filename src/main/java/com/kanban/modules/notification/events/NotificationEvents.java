package com.kanban.modules.notification.events;

public final class NotificationEvents {
  private NotificationEvents() {}

  public static final String COMMENT_CREATED = "notification.comment.created";
  public static final String COMMENT_MENTIONED = "notification.comment.mentioned";
  public static final String TASK_ASSIGNED = "notification.task.assigned";
  public static final String TASK_UPDATED = "notification.task.updated";
}
