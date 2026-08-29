package com.kanban.modules.task;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.task.dto.CreateSubtaskDto;
import com.kanban.modules.task.dto.CreateTaskDto;
import com.kanban.modules.task.dto.ManageAssigneesDto;
import com.kanban.modules.task.dto.ManageLabelsDto;
import com.kanban.modules.task.dto.MoveTaskDto;
import com.kanban.modules.task.dto.ReorderSubtaskDto;
import com.kanban.modules.task.dto.ReorderTaskDto;
import com.kanban.modules.task.dto.UpdateTaskDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tasks")
@RestController
@RequestMapping("/tasks")
public class TaskController {
  private final TaskService taskService;

  public TaskController(TaskService taskService) {
    this.taskService = taskService;
  }

  private static Map<String, Object> full(Task task) {
    return task.toJson(Task.FULL_RELATIONS);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a task")
  @ApiResponse(responseCode = "201", description = "Task created")
  public Map<String, Object> create(@ValidatedBody CreateTaskDto dto, @CurrentUser("id") String userId) {
    return full(taskService.create(dto, userId));
  }

  @GetMapping
  @Operation(summary = "Get all tasks")
  @ApiResponse(responseCode = "200", description = "List of tasks")
  public List<Map<String, Object>> findAll() {
    return taskService.findAll().stream().map(t -> t.toJson(TaskService.FIND_ALL_RELATIONS)).toList();
  }

  @GetMapping("/by-ticket/{ticketId}")
  @Operation(summary = "Get a task by ticket ID (e.g. KAN-1)")
  @Parameter(name = "ticketId", description = "Ticket ID (e.g. KAN-1, WEB-3)")
  @ApiResponse(responseCode = "200", description = "Task found")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> findByTicketId(@Param("ticketId") String ticketId) {
    return full(taskService.findByTicketId(ticketId));
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a task by ID")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Task found")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.UUID) String id) {
    return full(taskService.findOneById(id));
  }

  @PatchMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Update a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Task updated")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody UpdateTaskDto dto, @CurrentUser("id") String userId) {
    return full(taskService.update(id, dto, userId));
  }

  @PatchMapping("/{id}/reorder")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Reorder a task within the same column")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Task reordered")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> reorder(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ReorderTaskDto dto, @CurrentUser("id") String userId) {
    return full(taskService.reorder(id, dto.position, userId));
  }

  @PatchMapping("/{id}/move")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Move a task to a different column")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Task moved")
  @ApiResponse(responseCode = "404", description = "Task or column not found")
  public Map<String, Object> move(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody MoveTaskDto dto, @CurrentUser("id") String userId) {
    return full(taskService.move(id, dto.column_id, dto.position, userId));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Task deleted")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.UUID) String id) {
    taskService.remove(id);
  }

  @PostMapping("/{id}/subtasks")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a subtask under a parent task")
  @Parameter(name = "id", description = "Parent task UUID")
  @ApiResponse(responseCode = "201", description = "Subtask created")
  @ApiResponse(responseCode = "400", description = "Cannot nest subtasks deeper than 1 level")
  @ApiResponse(responseCode = "404", description = "Parent task not found")
  public Map<String, Object> createSubtask(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody CreateSubtaskDto dto, @CurrentUser("id") String userId) {
    return full(taskService.createSubtask(id, dto, userId));
  }

  @GetMapping("/{id}/subtasks")
  @Operation(summary = "List all subtasks of a task")
  @Parameter(name = "id", description = "Parent task UUID")
  @ApiResponse(responseCode = "200", description = "List of subtasks")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public ApiListResponse<Map<String, Object>> findSubtasks(@Param(value = "id", pipe = Param.Pipe.UUID) String id) {
    return taskService.findSubtasks(id);
  }

  @PatchMapping("/{id}/subtasks/{subtaskId}/reorder")
  @Operation(summary = "Reorder a subtask within its parent")
  @Parameter(name = "id", description = "Parent task UUID")
  @Parameter(name = "subtaskId", description = "Subtask UUID")
  @ApiResponse(responseCode = "200", description = "Subtask reordered")
  @ApiResponse(responseCode = "400", description = "Subtask does not belong to parent")
  @ApiResponse(responseCode = "404", description = "Task or subtask not found")
  public Map<String, Object> reorderSubtask(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @Param(value = "subtaskId", pipe = Param.Pipe.UUID) String subtaskId, @ValidatedBody ReorderSubtaskDto dto) {
    return full(taskService.reorderSubtask(id, subtaskId, dto.position));
  }

  @PostMapping("/{id}/assignees")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Add assignees to a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "201", description = "Assignees added")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> addAssignees(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageAssigneesDto dto, @CurrentUser("id") String userId) {
    return full(taskService.addAssignees(id, dto.user_ids, userId));
  }

  @DeleteMapping("/{id}/assignees")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove assignees from a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Assignees removed")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> removeAssignees(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageAssigneesDto dto, @CurrentUser("id") String userId) {
    return full(taskService.removeAssignees(id, dto.user_ids, userId));
  }

  @PostMapping("/{id}/labels")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Add labels to a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "201", description = "Labels added")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> addLabels(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageLabelsDto dto, @CurrentUser("id") String userId) {
    return full(taskService.addLabels(id, dto.label_ids, userId));
  }

  @DeleteMapping("/{id}/labels")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Remove labels from a task")
  @Parameter(name = "id", description = "Task UUID")
  @ApiResponse(responseCode = "200", description = "Labels removed")
  @ApiResponse(responseCode = "404", description = "Task not found")
  public Map<String, Object> removeLabels(@Param(value = "id", pipe = Param.Pipe.UUID) String id,
      @ValidatedBody ManageLabelsDto dto, @CurrentUser("id") String userId) {
    return full(taskService.removeLabels(id, dto.label_ids, userId));
  }
}
