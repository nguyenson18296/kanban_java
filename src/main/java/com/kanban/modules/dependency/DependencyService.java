package com.kanban.modules.dependency;

import com.kanban.common.events.EventBus;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.activity.events.TaskActivityAction;
import com.kanban.modules.activity.events.TaskActivityEvent;
import com.kanban.modules.dependency.dto.TaskDependenciesResponseDto;
import com.kanban.modules.dependency.dto.TaskSummaryDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DependencyService {
  private final DependencyRepository repository;
  private final DependencyQueries queries;
  private final ProjectAccessService projectAccessService;
  private final EventBus eventBus;

  public DependencyService(DependencyRepository repository, DependencyQueries queries,
      ProjectAccessService projectAccessService, EventBus eventBus) {
    this.repository = repository;
    this.queries = queries;
    this.projectAccessService = projectAccessService;
    this.eventBus = eventBus;
  }

  public TaskDependenciesResponseDto list(String taskId, String actorId) {
    projectAccessService.ensureTaskRole(taskId, actorId, ProjectRole.VIEWER);
    return view(taskId);
  }

  @Transactional
  public TaskDependenciesResponseDto add(String taskId, List<String> blockedByIds, String actorId) {
    String projectId = projectAccessService.ensureTaskRole(taskId, actorId, ProjectRole.MEMBER);
    List<String> ids = canonicalize(blockedByIds);

    // Before any check, so the check and the insert are both inside the lock: two concurrent
    // adds in one project must not each pass the cycle check and jointly close a loop.
    queries.lockProjectDependencies(projectId);

    Map<String, TaskSummaryDto> targets = resolveTargets(ids, projectId);
    List<String> offenders = queries.findReachableFrom(taskId, ids);
    if (!offenders.isEmpty()) {
      throw cycleConflict(taskId, offenders, targets);
    }

    // Which edges are genuinely new, so activity reports only those.
    Set<String> existing = new HashSet<>(repository.findExistingBlockers(taskId, ids));
    List<String> created = ids.stream().filter(id -> !existing.contains(id)).toList();
    for (String blockerId : created) {
      repository.insertIgnore(blockerId, taskId, actorId);
    }
    if (!created.isEmpty()) {
      emitActivity(actorId, taskId, TaskActivityAction.TASK_DEPENDENCY_ADDED, created, targets);
    }
    return view(taskId);
  }

  /** Removing edges cannot close a loop, so this needs neither the lock nor the cycle check. */
  @Transactional
  public void remove(String taskId, List<String> blockedByIds, String actorId) {
    String projectId = projectAccessService.ensureTaskRole(taskId, actorId, ProjectRole.MEMBER);
    List<String> ids = canonicalize(blockedByIds);

    // Deliberately NOT resolveTargets: membership on taskId is what authorizes this, and only
    // edges pointing at taskId can be deleted, so a blocker outside the project leaks nothing.
    // Checking it would make an edge unremovable once its blocker moved to another project
    // (PATCH /tasks/:id/move allows that) while GET still listed it.
    //
    // Read the edges before deleting: the delete count alone cannot say which ones went.
    List<String> removed = repository.findExistingBlockers(taskId, ids);
    if (removed.isEmpty()) {
      return;
    }
    repository.deleteEdges(taskId, removed);
    emitActivity(actorId, taskId, TaskActivityAction.TASK_DEPENDENCY_REMOVED, removed,
        summariesIn(removed, projectId));
  }

  private TaskDependenciesResponseDto view(String taskId) {
    return new TaskDependenciesResponseDto(repository.findBlockedBy(taskId), repository.findBlocks(taskId));
  }

  /**
   * Postgres stores and returns uuids in canonical lowercase, while the UUID validators accept any
   * casing. Folding here keeps every later string comparison against DB-read ids stable (the
   * summary map keys, the existing-edge diff) and collapses two casings of one id into one entry.
   */
  private static List<String> canonicalize(List<String> ids) {
    return ids.stream().map(id -> id.toLowerCase(Locale.ROOT)).distinct().toList();
  }

  /**
   * Unknown, deleted and cross-project ids are all rejected with the same task-flavored 404, so the
   * response never confirms that a task exists in a project the caller cannot see.
   */
  private Map<String, TaskSummaryDto> resolveTargets(List<String> ids, String projectId) {
    Map<String, TaskSummaryDto> found = summariesIn(ids, projectId);
    for (String id : ids) {
      if (!found.containsKey(id)) {
        throw taskNotFound(id);
      }
    }
    return found;
  }

  /** Best-effort labels for activity payloads; an id outside the project simply yields nulls. */
  private Map<String, TaskSummaryDto> summariesIn(List<String> ids, String projectId) {
    Map<String, TaskSummaryDto> found = new LinkedHashMap<>();
    for (TaskSummaryDto task : queries.findTasksInProject(ids, projectId)) {
      found.put(task.id(), task);
    }
    return found;
  }

  private static ConflictException cycleConflict(String taskId, List<String> offenders,
      Map<String, TaskSummaryDto> targets) {
    if (offenders.contains(taskId)) {
      return new ConflictException(Json.map("statusCode", 409, "message", "A task cannot block itself"));
    }
    String offender = offenders.get(0);
    TaskSummaryDto task = targets.get(offender);
    String label = task != null && task.ticket_id() != null ? task.ticket_id() : offender;
    return new ConflictException(Json.map("statusCode", 409,
        "message", "Adding \"" + label + "\" would create a dependency cycle"));
  }

  private void emitActivity(String actorId, String taskId, TaskActivityAction action, List<String> ids,
      Map<String, TaskSummaryDto> targets) {
    List<Object> dependencies = new ArrayList<>();
    for (String id : ids) {
      TaskSummaryDto task = targets.get(id);
      dependencies.add(Json.map(
          "task_id", id,
          "ticket_id", task == null ? null : task.ticket_id(),
          "title", task == null ? null : task.title()));
    }
    eventBus.emit(new TaskActivityEvent(actorId, taskId, action, Json.map("dependencies", dependencies)));
  }

  /** Byte-identical to ProjectAccessService's task-flavored 404. */
  private static NotFoundException taskNotFound(String taskId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + taskId + "\" not found"));
  }
}
