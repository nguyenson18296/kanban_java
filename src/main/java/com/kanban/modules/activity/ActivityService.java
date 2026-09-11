package com.kanban.modules.activity;

import com.kanban.common.api.PaginatedResponse;
import com.kanban.common.api.PaginationMeta;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.activity.dto.ActivityQueryDto;
import com.kanban.modules.activity.events.TaskActivityAction;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {
  private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

  private final ActivityRepository activityRepository;
  private final ProjectAccessService projectAccessService;

  public ActivityService(ActivityRepository activityRepository, ProjectAccessService projectAccessService) {
    this.activityRepository = activityRepository;
    this.projectAccessService = projectAccessService;
  }

  public Activity create(String taskId, String actorId, TaskActivityAction action, Map<String, Object> payload) {
    Activity activity = new Activity();
    activity.setTaskId(taskId);
    activity.setActorId(actorId);
    activity.setAction(action);
    activity.setPayload(payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload));
    return activityRepository.save(activity);
  }

  public PaginatedResponse<Map<String, Object>> findByTask(String taskId, ActivityQueryDto query, String userId) {
    projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);
    try {
      int page = query.page == null ? 1 : query.page;
      int limit = query.limit == null ? 20 : query.limit;
      Page<Activity> result = activityRepository.findByTaskFiltered(taskId, query.action,
          PageRequest.of(page - 1, limit));
      return new PaginatedResponse<>(result.getContent().stream().map(Activity::toJsonWithActor).toList(),
          PaginationMeta.of(page, limit, result.getTotalElements()));
    } catch (HttpException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch task activities", e);
      throw new InternalServerErrorException(Json.map(
          "statusCode", 500, "message", "Failed to fetch task activities", "error", PgErrors.message(e)));
    }
  }

  @SuppressWarnings("unused")
  private static HttpException unused() {
    return null;
  }
}
