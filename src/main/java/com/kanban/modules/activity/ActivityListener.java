package com.kanban.modules.activity;

import com.kanban.modules.activity.events.ActivityEvents;
import com.kanban.modules.activity.events.TaskActivityEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/** One handler per ACTIVITY_EVENTS name in Nest; here a single typed listener dispatching on the action. */
@Component
public class ActivityListener {
  private static final Logger log = LoggerFactory.getLogger(ActivityListener.class);
  private final ActivityService activityService;

  public ActivityListener(ActivityService activityService) {
    this.activityService = activityService;
  }

  @Async("eventExecutor")
  @EventListener
  public void handleTaskActivity(TaskActivityEvent event) {
    String eventName = ActivityEvents.nameOf(event.action()).replaceFirst("^activity\\.", "");
    try {
      activityService.create(event.task_id(), event.actor_id(), event.action(), event.payload());
    } catch (RuntimeException e) {
      log.error("Failed to handle activity.{} event", eventName, e);
    }
  }
}
