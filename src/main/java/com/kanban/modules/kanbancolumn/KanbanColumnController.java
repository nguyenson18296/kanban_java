package com.kanban.modules.kanbancolumn;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.kanbancolumn.dto.CreateKanbanColumnDto;
import com.kanban.modules.kanbancolumn.dto.UpdateKanbanColumnDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Columns")
@RestController
@RequestMapping("/columns")
public class KanbanColumnController {
  private final KanbanColumnService columnService;

  public KanbanColumnController(KanbanColumnService columnService) {
    this.columnService = columnService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a kanban column (admin+ on the target project)")
  @ApiResponse(responseCode = "201", description = "Column created")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  @ApiResponse(responseCode = "409", description = "Column name already exists")
  public Map<String, Object> create(@ValidatedBody CreateKanbanColumnDto dto, @CurrentUser("id") String actorId) {
    return columnService.create(dto, actorId).toJson();
  }

  @GetMapping
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get active columns for the caller's projects (ordered by position)")
  @ApiResponse(responseCode = "200", description = "List of columns")
  public ApiListResponse<Map<String, Object>> findAll(@CurrentUser("id") String actorId) {
    return columnService.findAll(actorId);
  }

  @GetMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get a column by ID (any project member)")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column found")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Column not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.INT) int id,
      @CurrentUser("id") String actorId) {
    return columnService.findOneById(id, actorId).toJson();
  }

  @PatchMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Update a column (admin+)")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column updated")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Column not found")
  @ApiResponse(responseCode = "409", description = "Column name already exists")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.INT) int id,
      @ValidatedBody UpdateKanbanColumnDto dto, @CurrentUser("id") String actorId) {
    return columnService.update(id, dto, actorId).toJson();
  }

  @DeleteMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Delete a column (admin+)")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column deleted")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Column not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.INT) int id, @CurrentUser("id") String actorId) {
    columnService.remove(id, actorId);
  }
}
