package com.kanban.modules.board;

import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.InternalServerErrorException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.common.util.PgErrors;
import com.kanban.modules.board.dto.BoardQueryDto;
import com.kanban.modules.board.dto.BoardResponse;
import com.kanban.modules.kanbancolumn.KanbanColumn;
import com.kanban.modules.kanbancolumn.KanbanColumnRepository;
import com.kanban.modules.label.Label;
import com.kanban.modules.project.ProjectRepository;
import com.kanban.modules.task.Task;
import com.kanban.modules.user.User;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BoardService {
  private static final Logger log = LoggerFactory.getLogger(BoardService.class);

  private final KanbanColumnRepository columnRepository;
  private final ProjectRepository projectRepository;
  private final BoardQueries boardQueries;

  public BoardService(KanbanColumnRepository columnRepository, ProjectRepository projectRepository,
      BoardQueries boardQueries) {
    this.columnRepository = columnRepository;
    this.projectRepository = projectRepository;
    this.boardQueries = boardQueries;
  }

  public BoardResponse getBoard(String projectId, BoardQueryDto query) {
    try {
      if (!projectRepository.existsById(projectId)) {
        throw new NotFoundException(Json.map(
            "statusCode", 404,
            "message", "Project with id \"" + projectId + "\" not found"));
      }
      List<KanbanColumn> columns = columnRepository.findByIsArchivedFalseAndProjectIdOrderByPositionAsc(projectId);
      if (columns.isEmpty()) {
        return new BoardResponse(List.of());
      }
      List<Integer> columnIds = columns.stream().map(KanbanColumn::getId).toList();
      BoardQueries.Filters filters = new BoardQueries.Filters(
          columnIds,
          query.priority == null ? null : query.priority.value(),
          query.search == null || query.search.isBlank() ? null : query.search,
          query.assigneeId == null || query.assigneeId.isEmpty() ? null : query.assigneeId,
          query.labelId == null || query.labelId == 0 ? null : query.labelId);

      Map<Integer, Long> countMap = boardQueries.countPerColumn(filters);
      List<Task> limitedTasks = boardQueries.topTasksPerColumn(filters, query.tasksPerColumn);

      Map<Integer, List<Task>> tasksByColumn = new LinkedHashMap<>();
      for (Task task : limitedTasks) {
        tasksByColumn.computeIfAbsent(task.getColumnId(), k -> new ArrayList<>()).add(task);
      }
      List<BoardResponse.BoardColumn> out = new ArrayList<>();
      for (KanbanColumn col : columns) {
        List<BoardResponse.BoardTask> tasks = new ArrayList<>();
        for (Task t : tasksByColumn.getOrDefault(col.getId(), List.of())) {
          List<BoardResponse.BoardAssignee> assignees = new ArrayList<>();
          for (User a : t.getAssignees()) {
            assignees.add(new BoardResponse.BoardAssignee(a.getId(), a.getFullName(), a.getAvatarUrl()));
          }
          List<BoardResponse.BoardLabel> labels = new ArrayList<>();
          for (Label l : t.getLabels()) {
            labels.add(new BoardResponse.BoardLabel(l.getId(), l.getName(), l.getColor()));
          }
          tasks.add(new BoardResponse.BoardTask(t.getId(), t.getTitle(), t.getTicketId(), t.getPosition(),
              t.getStatus(), t.getPriority(), t.getCreatedAt(), t.getDueDate(), assignees, labels));
        }
        out.add(new BoardResponse.BoardColumn(col.getId(), col.getName(), col.getPosition(), col.getColor(),
            countMap.getOrDefault(col.getId(), 0L), tasks));
      }
      return new BoardResponse(out);
    } catch (NotFoundException e) {
      throw e;
    } catch (RuntimeException e) {
      log.error("Failed to fetch board", e);
      throw internal("Failed to fetch board", e);
    }
  }

  private static HttpException internal(String message, Throwable cause) {
    return new InternalServerErrorException(Json.map(
        "statusCode", 500, "message", message, "error", PgErrors.message(cause)));
  }
}
