package com.kanban.modules.activity.events;

/** Event names (ACTIVITY_EVENTS) — kept for parity/logging; dispatch is by payload type. */
public final class ActivityEvents {
  private ActivityEvents() {}

  public static final String TASK_CREATED = "activity.task.created";
  public static final String TASK_TITLE_UPDATED = "activity.task.title_updated";
  public static final String TASK_DESCRIPTION_UPDATED = "activity.task.description_updated";
  public static final String TASK_STATUS_CHANGED = "activity.task.status_changed";
  public static final String TASK_PRIORITY_CHANGED = "activity.task.priority_changed";
  public static final String TASK_DUE_DATE_CHANGED = "activity.task.due_date_changed";
  public static final String TASK_ASSIGNEE_ADDED = "activity.task.assignee_added";
  public static final String TASK_ASSIGNEE_REMOVED = "activity.task.assignee_removed";
  public static final String TASK_LABEL_ADDED = "activity.task.label_added";
  public static final String TASK_LABEL_REMOVED = "activity.task.label_removed";
  public static final String TASK_MOVED = "activity.task.moved";
  public static final String TASK_REORDERED = "activity.task.reordered";

  public static String nameOf(TaskActivityAction action) {
    return switch (action) {
      case TASK_CREATED -> TASK_CREATED;
      case TASK_TITLE_UPDATED -> TASK_TITLE_UPDATED;
      case TASK_DESCRIPTION_UPDATED -> TASK_DESCRIPTION_UPDATED;
      case TASK_STATUS_CHANGED -> TASK_STATUS_CHANGED;
      case TASK_PRIORITY_CHANGED -> TASK_PRIORITY_CHANGED;
      case TASK_DUE_DATE_CHANGED -> TASK_DUE_DATE_CHANGED;
      case TASK_ASSIGNEE_ADDED -> TASK_ASSIGNEE_ADDED;
      case TASK_ASSIGNEE_REMOVED -> TASK_ASSIGNEE_REMOVED;
      case TASK_LABEL_ADDED -> TASK_LABEL_ADDED;
      case TASK_LABEL_REMOVED -> TASK_LABEL_REMOVED;
      case TASK_MOVED -> TASK_MOVED;
      case TASK_REORDERED -> TASK_REORDERED;
    };
  }
}
