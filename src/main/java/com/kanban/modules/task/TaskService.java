package com.kanban.modules.task;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.events.EventBus;
import com.kanban.common.exception.BadRequestException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.Dates;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.activity.events.ActivityEvents;
import com.kanban.modules.activity.events.TaskActivityAction;
import com.kanban.modules.activity.events.TaskActivityEvent;
import com.kanban.modules.kanbancolumn.KanbanColumn;
import com.kanban.modules.kanbancolumn.KanbanColumnRepository;
import com.kanban.modules.label.Label;
import com.kanban.modules.label.LabelRepository;
import com.kanban.modules.mention.MentionService;
import com.kanban.modules.notification.events.NotificationEvents;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import com.kanban.modules.notification.events.TaskUpdatedEvent;
import com.kanban.modules.subscription.SubscriptionService;
import com.kanban.modules.subscription.SubscriptionSource;
import com.kanban.modules.task.dto.CreateSubtaskDto;
import com.kanban.modules.task.dto.CreateTaskDto;
import com.kanban.modules.task.dto.UpdateTaskDto;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
  private static final Logger log = LoggerFactory.getLogger(TaskService.class);

  /** relations loaded by findAll: assignees, labels, creator, subtasks, subtasks.parent */
  static final Set<String> FIND_ALL_RELATIONS = Set.of(Task.REL_ASSIGNEES, Task.REL_LABELS, Task.REL_CREATOR,
      Task.REL_SUBTASKS, Task.REL_SUBTASKS_PARENT);
  /** relations loaded by findSubtasks: assignees, labels, creator, subtasks, parent */
  static final Set<String> SUBTASK_RELATIONS = Set.of(Task.REL_ASSIGNEES, Task.REL_LABELS, Task.REL_CREATOR,
      Task.REL_SUBTASKS, Task.REL_PARENT);

  private final TaskRepository taskRepository;
  private final UserRepository userRepository;
  private final LabelRepository labelRepository;
  private final KanbanColumnRepository columnRepository;
  private final TaskPositionFunctions positionFunctions;
  private final EventBus eventBus;
  private final SubscriptionService subscriptionService;
  private final MentionService mentionService;

  public TaskService(TaskRepository taskRepository, UserRepository userRepository, LabelRepository labelRepository,
      KanbanColumnRepository columnRepository, TaskPositionFunctions positionFunctions, EventBus eventBus,
      SubscriptionService subscriptionService, MentionService mentionService) {
    this.taskRepository = taskRepository;
    this.userRepository = userRepository;
    this.labelRepository = labelRepository;
    this.columnRepository = columnRepository;
    this.positionFunctions = positionFunctions;
    this.eventBus = eventBus;
    this.subscriptionService = subscriptionService;
    this.mentionService = mentionService;
  }

  private void ensureTaskExists(String id) {
    if (!taskRepository.existsById(id)) {
      throw taskNotFound(id);
    }
  }

  public Task create(CreateTaskDto dto, String actorId) {
    try {
      if (dto.parent_id != null) {
        validateParent(dto.parent_id);
      }
      resolveColumn(dto.column_id);
      Task task = new Task();
      task.setTitle(dto.title);
      task.setColumnId(dto.column_id);
      if (dto.has("description")) {
        task.setDescription(dto.description);
      }
      if (dto.status != null) {
        task.setStatus(dto.status);
      }
      if (dto.priority != null) {
        task.setPriority(dto.priority);
      }
      if (dto.position != null) {
        task.setPosition(dto.position);
      }
      if (dto.has("team_id")) {
        task.setTeamId(dto.team_id);
      }
      if (dto.has("created_by")) {
        task.setCreatedBy(dto.created_by);
      }
      if (dto.has("due_date")) {
        task.setDueDate(dto.due_date);
      }
      if (dto.has("parent_id")) {
        task.setParentId(dto.parent_id);
      }
      List<String> assigneeIds = dto.assignee_ids;
      List<Integer> labelIds = dto.label_ids;
      if (assigneeIds != null && !assigneeIds.isEmpty()) {
        task.setAssignees(new LinkedHashSet<>(resolveUsers(assigneeIds)));
      }
      if (labelIds != null && !labelIds.isEmpty()) {
        task.setLabels(new LinkedHashSet<>(resolveLabels(labelIds)));
      }
      Task saved = taskRepository.save(task);
      Task result = findOneById(saved.getId());

      // KAN-78: subscriptions + assignment notification on create.
      if (actorId != null) {
        subscriptionService.subscribe(saved.getId(), actorId, SubscriptionSource.CREATED);
      }
      if (assigneeIds != null && !assigneeIds.isEmpty()) {
        subscriptionService.subscribeMany(saved.getId(), assigneeIds, SubscriptionSource.ASSIGNED);
        if (actorId != null) {
          eventBus.emit(NotificationEvents.TASK_ASSIGNED, new TaskAssignedEvent(actorId, saved.getId(), assigneeIds,
              Json.map("task_id", saved.getId(), "task_title", result.getTitle(), "ticket_id", result.getTicketId())));
        }
      }
      if (dto.description != null && !dto.description.isEmpty()) {
        subscribeDescriptionMentions(saved.getId(), dto.description, actorId != null ? List.of(actorId) : List.of());
      }
      if (actorId != null) {
        eventBus.emit(ActivityEvents.TASK_CREATED,
            new TaskActivityEvent(actorId, saved.getId(), TaskActivityAction.TASK_CREATED));
      }
      return result;
    } catch (NotFoundException | BadRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to create task", e);
      throw internal("Failed to create task", e);
    }
  }

  public List<Task> findAll() {
    try {
      return taskRepository.findTopLevelWithRelations();
    } catch (RuntimeException e) {
      log.error("Failed to fetch tasks", e);
      throw internal("Failed to fetch tasks", e);
    }
  }

  public Task findByTicketId(String ticketId) {
    try {
      return taskRepository.findByTicketIdWithFullRelations(ticketId).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Task with ticket_id \"" + ticketId + "\" not found")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch task by ticket_id", e);
      throw internal("Failed to fetch task by ticket_id", e);
    }
  }

  public Task findOneById(String id) {
    try {
      return taskRepository.findByIdWithFullRelations(id).orElseThrow(() -> taskNotFound(id));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch task", e);
      throw internal("Failed to fetch task", e);
    }
  }

  public Task update(String id, UpdateTaskDto dto, String actorId) {
    try {
      Task task = findOneById(id);
      TaskStatus previousStatus = task.getStatus();
      String previousTitle = task.getTitle();
      String previousDescription = task.getDescription();
      TaskPriority previousPriority = task.getPriority();
      Instant previousDueDate = task.getDueDate();
      List<User> previousAssignees = new ArrayList<>(task.getAssignees());
      Set<String> previousAssigneeIds = new HashSet<>();
      for (User u : previousAssignees) {
        previousAssigneeIds.add(u.getId());
      }
      List<Label> previousLabels = new ArrayList<>(task.getLabels());
      Set<Integer> previousLabelIds = new HashSet<>();
      for (Label l : previousLabels) {
        previousLabelIds.add(l.getId());
      }

      if (dto.has("parent_id")) {
        if (dto.parent_id != null) {
          if (dto.parent_id.equals(id)) {
            throw new BadRequestException(Json.map(
                "statusCode", 400,
                "message", "A task cannot be its own parent"));
          }
          if (task.getSubtasks() != null && !task.getSubtasks().isEmpty()) {
            throw new BadRequestException(Json.map(
                "statusCode", 400,
                "message", "Cannot make a task a subtask when it has its own subtasks (would exceed max depth of 1)"));
          }
          validateParent(dto.parent_id);
        }
      }
      // Object.assign(task, taskData) — only keys present in the payload
      if (dto.has("title")) {
        task.setTitle(dto.title);
      }
      if (dto.has("description")) {
        task.setDescription(dto.description);
      }
      if (dto.has("status") && dto.status != null) {
        task.setStatus(dto.status);
      }
      if (dto.has("priority") && dto.priority != null) {
        task.setPriority(dto.priority);
      }
      if (dto.has("column_id") && dto.column_id != null) {
        task.setColumnId(dto.column_id);
      }
      if (dto.has("position") && dto.position != null) {
        task.setPosition(dto.position);
      }
      if (dto.has("team_id")) {
        task.setTeamId(dto.team_id);
      }
      if (dto.has("created_by")) {
        task.setCreatedBy(dto.created_by);
      }
      if (dto.has("due_date")) {
        task.setDueDate(dto.due_date);
      }
      if (dto.has("parent_id")) {
        task.setParentId(dto.parent_id);
      }
      List<User> newAssignees = null;
      if (dto.has("assignee_ids") && dto.assignee_ids != null) {
        newAssignees = dto.assignee_ids.isEmpty() ? new ArrayList<>() : resolveUsers(dto.assignee_ids);
        task.setAssignees(new LinkedHashSet<>(newAssignees));
      }
      List<Label> newLabels = null;
      if (dto.has("label_ids") && dto.label_ids != null) {
        newLabels = dto.label_ids.isEmpty() ? new ArrayList<>() : resolveLabels(dto.label_ids);
        task.setLabels(new LinkedHashSet<>(newLabels));
      }
      taskRepository.save(task);
      Task updated = findOneById(id);

      // KAN-78: auto-subscribe newly-added assignees and description mentions,
      // then fan out a status change to all subscribers (minus the actor).
      if (actorId != null) {
        if (newAssignees != null) {
          List<String> addedIds = newAssignees.stream().map(User::getId)
              .filter(uid -> !previousAssigneeIds.contains(uid)).toList();
          if (!addedIds.isEmpty()) {
            subscriptionService.subscribeMany(task.getId(), addedIds, SubscriptionSource.ASSIGNED);
            eventBus.emit(NotificationEvents.TASK_ASSIGNED, new TaskAssignedEvent(actorId, task.getId(), addedIds,
                Json.map("task_id", task.getId(), "task_title", updated.getTitle(), "ticket_id", updated.getTicketId())));
          }
        }
        if (dto.has("description") && !Objects.equals(dto.description, previousDescription)
            && dto.description != null && !dto.description.isEmpty()) {
          subscribeDescriptionMentions(task.getId(), dto.description, List.of(actorId));
        }
        if (dto.has("status") && dto.status != null && dto.status != previousStatus) {
          List<String> subscriberIds = subscriptionService.getSubscriberIds(task.getId());
          List<String> recipients = subscriberIds.stream().filter(uid -> !uid.equals(actorId)).toList();
          if (!recipients.isEmpty()) {
            eventBus.emit(NotificationEvents.TASK_UPDATED, new TaskUpdatedEvent(actorId, task.getId(), recipients,
                Json.map(
                    "task_id", task.getId(),
                    "task_title", updated.getTitle(),
                    "ticket_id", updated.getTicketId(),
                    "changes", Json.map("status", Json.map("from", previousStatus, "to", dto.status)))));
          }
        }
      }
      // Emit activity events for changed fields
      if (actorId != null) {
        if (dto.has("title") && !Objects.equals(dto.title, previousTitle)) {
          emitActivity(actorId, task.getId(), TaskActivityAction.TASK_TITLE_UPDATED, Map.of());
        }
        if (dto.has("description") && !Objects.equals(dto.description, previousDescription)) {
          emitActivity(actorId, task.getId(), TaskActivityAction.TASK_DESCRIPTION_UPDATED, Map.of());
        }
        if (dto.has("status") && dto.status != null && dto.status != previousStatus) {
          emitActivity(actorId, task.getId(), TaskActivityAction.TASK_STATUS_CHANGED,
              Json.map("from", previousStatus, "to", dto.status));
        }
        if (dto.has("priority") && dto.priority != null && dto.priority != previousPriority) {
          emitActivity(actorId, task.getId(), TaskActivityAction.TASK_PRIORITY_CHANGED,
              Json.map("from", previousPriority, "to", dto.priority));
        }
        if (dto.has("due_date")) {
          Long prevTime = previousDueDate == null ? null : previousDueDate.toEpochMilli();
          Long newTime = dto.due_date == null ? null : dto.due_date.toEpochMilli();
          if (!Objects.equals(prevTime, newTime)) {
            emitActivity(actorId, task.getId(), TaskActivityAction.TASK_DUE_DATE_CHANGED, Json.map(
                "from", previousDueDate == null ? null : Dates.iso(previousDueDate),
                "to", dto.due_date == null ? null : Dates.iso(dto.due_date)));
          }
        }
        if (newAssignees != null) {
          Set<String> newAssigneeIds = new HashSet<>();
          for (User u : newAssignees) {
            newAssigneeIds.add(u.getId());
          }
          List<User> addedUsers = newAssignees.stream().filter(u -> !previousAssigneeIds.contains(u.getId())).toList();
          List<User> removedUsers = previousAssignees.stream().filter(u -> !newAssigneeIds.contains(u.getId())).toList();
          if (!addedUsers.isEmpty()) {
            emitActivity(actorId, task.getId(), TaskActivityAction.TASK_ASSIGNEE_ADDED,
                Json.map("users", usersPayload(addedUsers)));
          }
          if (!removedUsers.isEmpty()) {
            emitActivity(actorId, task.getId(), TaskActivityAction.TASK_ASSIGNEE_REMOVED,
                Json.map("users", usersPayload(removedUsers)));
          }
        }
        if (newLabels != null) {
          Set<Integer> newLabelIds = new HashSet<>();
          for (Label l : newLabels) {
            newLabelIds.add(l.getId());
          }
          List<Label> addedLabels = newLabels.stream().filter(l -> !previousLabelIds.contains(l.getId())).toList();
          List<Label> removedLabels = previousLabels.stream().filter(l -> !newLabelIds.contains(l.getId())).toList();
          if (!addedLabels.isEmpty()) {
            List<Object> labels = new ArrayList<>();
            for (Label l : addedLabels) {
              labels.add(Json.map("label_id", l.getId(), "label_name", l.getName(), "color", l.getColor()));
            }
            emitActivity(actorId, task.getId(), TaskActivityAction.TASK_LABEL_ADDED, Json.map("labels", labels));
          }
          if (!removedLabels.isEmpty()) {
            emitActivity(actorId, task.getId(), TaskActivityAction.TASK_LABEL_REMOVED,
                Json.map("labels", labelsPayload(removedLabels)));
          }
        }
      }
      return updated;
    } catch (NotFoundException | BadRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to update task", e);
      throw internal("Failed to update task", e);
    }
  }

  public void remove(String id) {
    try {
      findOneById(id);
      // delete through the id so Hibernate removes a managed instance (a detached
      // entity with its loaded subtasks graph would fail the merge step)
      taskRepository.deleteById(id);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to delete task", e);
      throw internal("Failed to delete task", e);
    }
  }

  public Task addAssignees(String taskId, List<String> userIds, String actorId) {
    try {
      Task task = findOneById(taskId);
      List<User> users = resolveUsers(userIds);
      Set<String> existingIds = new HashSet<>();
      for (User u : task.getAssignees()) {
        existingIds.add(u.getId());
      }
      List<User> newUsers = users.stream().filter(u -> !existingIds.contains(u.getId())).toList();
      Set<User> merged = new LinkedHashSet<>(task.getAssignees());
      merged.addAll(newUsers);
      task.setAssignees(merged);
      taskRepository.save(task);
      Task result = findOneById(taskId);
      // KAN-78: auto-subscribe newly-added assignees and notify them.
      if (!newUsers.isEmpty()) {
        List<String> newIds = newUsers.stream().map(User::getId).toList();
        subscriptionService.subscribeMany(taskId, newIds, SubscriptionSource.ASSIGNED);
        if (actorId != null) {
          eventBus.emit(NotificationEvents.TASK_ASSIGNED, new TaskAssignedEvent(actorId, taskId, newIds,
              Json.map("task_id", taskId, "task_title", result.getTitle(), "ticket_id", result.getTicketId())));
        }
      }
      if (actorId != null && !newUsers.isEmpty()) {
        emitActivity(actorId, taskId, TaskActivityAction.TASK_ASSIGNEE_ADDED, Json.map("users", usersPayload(newUsers)));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to add assignees", e);
      throw internal("Failed to add assignees", e);
    }
  }

  public Task removeAssignees(String taskId, List<String> userIds, String actorId) {
    try {
      Task task = findOneById(taskId);
      resolveUsers(userIds);
      Set<String> removeSet = new HashSet<>(userIds);
      List<User> removedUsers = task.getAssignees().stream().filter(u -> removeSet.contains(u.getId())).toList();
      Set<User> remaining = new LinkedHashSet<>();
      for (User u : task.getAssignees()) {
        if (!removeSet.contains(u.getId())) {
          remaining.add(u);
        }
      }
      task.setAssignees(remaining);
      taskRepository.save(task);
      Task result = findOneById(taskId);
      if (actorId != null && !removedUsers.isEmpty()) {
        emitActivity(actorId, taskId, TaskActivityAction.TASK_ASSIGNEE_REMOVED,
            Json.map("users", usersPayload(removedUsers)));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to remove assignees", e);
      throw internal("Failed to remove assignees", e);
    }
  }

  public Task addLabels(String taskId, List<Integer> labelIds, String actorId) {
    try {
      Task task = findOneById(taskId);
      List<Label> labels = resolveLabels(labelIds);
      Set<Integer> existingIds = new HashSet<>();
      for (Label l : task.getLabels()) {
        existingIds.add(l.getId());
      }
      List<Label> newLabels = labels.stream().filter(l -> !existingIds.contains(l.getId())).toList();
      Set<Label> merged = new LinkedHashSet<>(task.getLabels());
      merged.addAll(newLabels);
      task.setLabels(merged);
      taskRepository.save(task);
      Task result = findOneById(taskId);
      if (actorId != null && !newLabels.isEmpty()) {
        emitActivity(actorId, taskId, TaskActivityAction.TASK_LABEL_ADDED, Json.map("labels", labelsPayload(newLabels)));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to add labels", e);
      throw internal("Failed to add labels", e);
    }
  }

  public Task removeLabels(String taskId, List<Integer> labelIds, String actorId) {
    try {
      Task task = findOneById(taskId);
      resolveLabels(labelIds);
      Set<Integer> removeSet = new HashSet<>(labelIds);
      List<Label> removedLabels = task.getLabels().stream().filter(l -> removeSet.contains(l.getId())).toList();
      Set<Label> remaining = new LinkedHashSet<>();
      for (Label l : task.getLabels()) {
        if (!removeSet.contains(l.getId())) {
          remaining.add(l);
        }
      }
      task.setLabels(remaining);
      taskRepository.save(task);
      Task result = findOneById(taskId);
      if (actorId != null && !removedLabels.isEmpty()) {
        emitActivity(actorId, taskId, TaskActivityAction.TASK_LABEL_REMOVED,
            Json.map("labels", labelsPayload(removedLabels)));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to remove labels", e);
      throw internal("Failed to remove labels", e);
    }
  }

  public Task reorder(String id, int position, String actorId) {
    try {
      ensureTaskExists(id);
      positionFunctions.reorderTask(id, position);
      Task result = findOneById(id);
      if (actorId != null) {
        emitActivity(actorId, id, TaskActivityAction.TASK_REORDERED, Json.map("position", position));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to reorder task", e);
      throw internal("Failed to reorder task", e);
    }
  }

  public Task move(String id, int columnId, int position, String actorId) {
    try {
      List<Integer> current = taskRepository.findColumnIdRowById(id);
      if (current.isEmpty()) {
        throw taskNotFound(id);
      }
      Integer previousColumnId = current.get(0);
      resolveColumn(columnId);
      positionFunctions.moveTask(id, columnId, position);
      Task result = findOneById(id);
      if (actorId != null) {
        emitActivity(actorId, id, TaskActivityAction.TASK_MOVED, Json.map(
            "from_column_id", previousColumnId,
            "to_column_id", columnId,
            "position", position));
      }
      return result;
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to move task", e);
      throw internal("Failed to move task", e);
    }
  }

  public Task reorderSubtask(String parentId, String subtaskId, int position) {
    try {
      ensureTaskExists(parentId);
      // Atomically validates subtask belongs to parent and reorders
      positionFunctions.reorderSubtask(subtaskId, parentId, position);
      return findOneById(subtaskId);
    } catch (NotFoundException | BadRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to reorder subtask", e);
      throw internal("Failed to reorder subtask", e);
    }
  }

  public Task createSubtask(String parentId, CreateSubtaskDto dto, String actorId) {
    try {
      Task parent = findOneById(parentId);
      validateParent(parentId);
      int columnId = dto.column_id != null ? dto.column_id : parent.getColumnId();
      return create(dto.toCreateTaskDto(columnId, parentId), actorId);
    } catch (NotFoundException | BadRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to create subtask", e);
      throw internal("Failed to create subtask", e);
    }
  }

  public ApiListResponse<Map<String, Object>> findSubtasks(String parentId) {
    try {
      ensureTaskExists(parentId);
      List<Task> subtasks = taskRepository.findSubtasksWithRelations(parentId);
      return ApiListResponse.ok(subtasks.stream().map(t -> t.toJson(SUBTASK_RELATIONS)).toList());
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch subtasks", e);
      throw internal("Failed to fetch subtasks", e);
    }
  }

  /**
   * Best-effort: resolve @mentions in a task description and auto-subscribe them.
   * A mention-lookup failure must never fail the request — log and swallow.
   */
  private void subscribeDescriptionMentions(String taskId, String description, List<String> excludeIds) {
    try {
      List<String> mentionedIds = mentionService.resolveMentionedUserIds(description, excludeIds);
      if (!mentionedIds.isEmpty()) {
        subscriptionService.subscribeMany(taskId, mentionedIds, SubscriptionSource.MENTIONED);
      }
    } catch (RuntimeException e) {
      log.error("Failed to resolve or subscribe description mentions for task {}", taskId, e);
    }
  }

  private void validateParent(String parentId) {
    try {
      List<String> row = taskRepository.findParentIdRowById(parentId);
      if (row.isEmpty()) {
        throw new NotFoundException(Json.map(
            "statusCode", 404,
            "message", "Parent task with id \"" + parentId + "\" not found"));
      }
      if (row.get(0) != null) {
        throw new BadRequestException(Json.map(
            "statusCode", 400,
            "message", "Cannot create a subtask of a subtask (max depth is 1)"));
      }
    } catch (NotFoundException | BadRequestException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to validate parent", e);
      throw internal("Failed to validate parent", e);
    }
  }

  private List<User> resolveUsers(List<String> ids) {
    List<User> users = userRepository.findByIdIn(ids);
    if (users.size() != ids.size()) {
      Set<String> found = new HashSet<>();
      for (User u : users) {
        found.add(u.getId());
      }
      List<String> missing = ids.stream().filter(id -> !found.contains(id)).toList();
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Users not found: " + String.join(", ", missing)));
    }
    return users;
  }

  private KanbanColumn resolveColumn(Integer id) {
    return columnRepository.findById(id == null ? -1 : id).orElseThrow(() -> new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Column with id \"" + id + "\" not found")));
  }

  private List<Label> resolveLabels(List<Integer> ids) {
    List<Label> labels = labelRepository.findByIdIn(ids);
    if (labels.size() != ids.size()) {
      Set<Integer> found = new HashSet<>();
      for (Label l : labels) {
        found.add(l.getId());
      }
      List<String> missing = ids.stream().filter(id -> !found.contains(id)).map(String::valueOf).toList();
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Labels not found: " + String.join(", ", missing)));
    }
    return labels;
  }

  private void emitActivity(String actorId, String taskId, TaskActivityAction action, Map<String, Object> payload) {
    eventBus.emit(ActivityEvents.nameOf(action), new TaskActivityEvent(actorId, taskId, action, payload));
  }

  private static List<Object> usersPayload(List<User> users) {
    List<Object> out = new ArrayList<>();
    for (User u : users) {
      out.add(Json.map("user_id", u.getId(), "full_name", u.getFullName()));
    }
    return out;
  }

  private static List<Object> labelsPayload(List<Label> labels) {
    List<Object> out = new ArrayList<>();
    for (Label l : labels) {
      out.add(Json.map("label_id", l.getId(), "label_name", l.getName()));
    }
    return out;
  }

  private static NotFoundException taskNotFound(String id) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + id + "\" not found"));
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
