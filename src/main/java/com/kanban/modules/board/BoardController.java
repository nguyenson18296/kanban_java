package com.kanban.modules.board;

import com.kanban.common.pipes.Param;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.common.validation.ValidatedQuery;
import com.kanban.modules.board.dto.BoardQueryDto;
import com.kanban.modules.board.dto.BoardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Board")
@JwtAuth
@RestController
@RequestMapping("/board")
public class BoardController {
  private final BoardService boardService;

  public BoardController(BoardService boardService) {
    this.boardService = boardService;
  }

  @GetMapping("/{projectId}")
  @Operation(summary = "Get board with columns and tasks for a project",
      description = "Returns all active columns with their tasks, assignees, and labels for the given project. "
          + "Supports filtering and per-column pagination.")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "Board data")
  @ApiResponse(responseCode = "401", description = "Missing or invalid token")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public BoardResponse getBoard(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @ValidatedQuery BoardQueryDto query) {
    return boardService.getBoard(projectId, query);
  }
}
