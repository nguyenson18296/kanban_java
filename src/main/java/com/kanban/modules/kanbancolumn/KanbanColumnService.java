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
import com.kanban.modules.project.ProjectRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KanbanColumnService {
  private static final Logger log = LoggerFactory.getLogger(KanbanColumnService.class);

  private final KanbanColumnRepository columnRepository;
  private final ProjectRepository projectRepository;

  public KanbanColumnService(KanbanColumnRepository columnRepository, ProjectRepository projectRepository) {
    this.columnRepository = columnRepository;
    this.projectRepository = projectRepository;
  }

  public KanbanColumn create(CreateKanbanColumnDto dto) {
    try {
      if (!projectRepository.existsById(dto.project_id)) {
        throw projectNotFound(dto.project_id);
      }
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

  public ApiListResponse<Map<String, Object>> findAll() {
    try {
      return ApiListResponse.ok(columnRepository.findByIsArchivedFalseOrderByPositionAsc().stream()
          .map(KanbanColumn::toJson).toList());
    } catch (RuntimeException e) {
      log.error("Failed to fetch columns", e);
      throw internal("Failed to fetch columns", e);
    }
  }

  public KanbanColumn findOneById(int id) {
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

  public KanbanColumn update(int id, UpdateKanbanColumnDto dto) {
    try {
      if (dto.project_id != null && !dto.project_id.isEmpty() && !projectRepository.existsById(dto.project_id)) {
        throw projectNotFound(dto.project_id);
      }
      KanbanColumn column = findOneById(id);
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
    } catch (NotFoundException | ConflictException e) {
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

  public void remove(int id) {
    try {
      findOneById(id);
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

  private static NotFoundException projectNotFound(String projectId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Project with id \"" + projectId + "\" not found"));
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
