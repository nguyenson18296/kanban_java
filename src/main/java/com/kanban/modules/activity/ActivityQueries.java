package com.kanban.modules.activity;

import com.kanban.modules.activity.events.TaskActivityAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ActivityQueries {
  Page<Activity> findByTaskFiltered(String taskId, TaskActivityAction action, Pageable pageable);
}
