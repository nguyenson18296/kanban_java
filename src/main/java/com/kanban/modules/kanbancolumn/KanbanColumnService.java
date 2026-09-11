package com.kanban.modules.kanbancolumn;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.kanbancolumn.dto.CreateKanbanColumnDto;
import com.kanban.modules.kanbancolumn.dto.UpdateKanbanColumnDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KanbanColumnService {
  private static final Logger log = LoggerFactory.getLogger(KanbanColumnService.class);

  private final KanbanColumnRepository columnRepository;
  private final ProjectAccessService projectAccessService;

  public KanbanColumnService(KanbanColumnRepository columnRepository, ProjectAccessService projectAccessService) {
    this.columnRepository = columnRepository;
    this.projectAccessService = projectAccessService;
  }

  public KanbanColumn create(CreateKanbanColumnDto dto, String actorId) {
    projectAccessService.ensureRole(dto.project_id, actorId, ProjectRole.ADMIN);
    try {
      KanbanColumn column = new KanbanColumn();
      column.setName(dto.name);
      column.setProjectId(dto.project_id);
      if (dto.has("position") && dto.position != null) {
        column.setPosition(dto.position);
      }
      if (dto.has("color")) {
        column.setColor(dto.color);
      }
      return columnRepository.saveAndFlush(column);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Column with name \"" + dto.name + "\" already exists",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to create column", error);
      throw internal("Failed to create column", error);
    }
  }

  public ApiListResponse<Map<String, Object>> findAll(String actorId) {
    List<String> projectIds = projectAccessService.getProjectIdsForUser(actorId);
    if (projectIds.isEmpty()) {
      return ApiListResponse.ok(List.of());
    }
    try {
      return ApiListResponse.ok(columnRepository.findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(projectIds)
          .stream().map(KanbanColumn::toJson).toList());
    } catch (RuntimeException e) {
      log.error("Failed to fetch columns", e);
      throw internal("Failed to fetch columns", e);
    }
  }

  public KanbanColumn findOneById(int id, String actorId) {
    projectAccessService.ensureColumnRole(id, actorId, ProjectRole.VIEWER);
    return getColumnOrThrow(id);
  }

  private KanbanColumn getColumnOrThrow(int id) {
    try {
      return columnRepository.findById(id).orElseThrow(() -> new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Column with id \"" + id + "\" not found")));
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch column", e);
      throw internal("Failed to fetch column", e);
    }
  }

  public KanbanColumn update(int id, UpdateKanbanColumnDto dto, String actorId) {
    projectAccessService.ensureColumnRole(id, actorId, ProjectRole.ADMIN);
    try {
      KanbanColumn column = getColumnOrThrow(id);
      // Moving the column to a different project requires admin on the target project too;
      // ensureRole masks a nonexistent/forbidden target as the same 404 (no existence oracle).
      if (dto.project_id != null && !dto.project_id.isEmpty() && !dto.project_id.equals(column.getProjectId())) {
        projectAccessService.ensureRole(dto.project_id, actorId, ProjectRole.ADMIN);
      }
      if (dto.has("name")) {
        column.setName(dto.name);
      }
      if (dto.has("project_id")) {
        column.setProjectId(dto.project_id);
      }
      if (dto.has("position") && dto.position != null) {
        column.setPosition(dto.position);
      }
      if (dto.has("color")) {
        column.setColor(dto.color);
      }
      return columnRepository.saveAndFlush(column);
    } catch (HttpException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.UNIQUE_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Column with name \"" + dto.name + "\" already exists",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to update column", error);
      throw internal("Failed to update column", error);
    }
  }

  public void remove(int id, String actorId) {
    projectAccessService.ensureColumnRole(id, actorId, ProjectRole.ADMIN);
    try {
      getColumnOrThrow(id);
      columnRepository.deleteById(id);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException error) {
      if (PgErrors.isCode(error, PgErrors.FOREIGN_KEY_VIOLATION)) {
        throw new ConflictException(Json.map(
            "statusCode", 409,
            "message", "Cannot delete column with existing tasks",
            "error", PgErrors.message(error)));
      }
      log.error("Failed to delete column", error);
      throw internal("Failed to delete column", error);
    }
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
