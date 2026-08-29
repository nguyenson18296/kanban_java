package com.kanban.modules.kanbancolumn;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.kanbancolumn.dto.CreateKanbanColumnDto;
import com.kanban.modules.kanbancolumn.dto.UpdateKanbanColumnDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
  @Operation(summary = "Create a kanban column")
  @ApiResponse(responseCode = "201", description = "Column created")
  @ApiResponse(responseCode = "409", description = "Column name already exists")
  public Map<String, Object> create(@ValidatedBody CreateKanbanColumnDto dto) {
    return columnService.create(dto).toJson();
  }

  @GetMapping
  @Operation(summary = "Get all active columns (ordered by position)")
  @ApiResponse(responseCode = "200", description = "List of columns")
  public ApiListResponse<Map<String, Object>> findAll() {
    return columnService.findAll();
  }

  @GetMapping("/{id}")
  @Operation(summary = "Get a column by ID")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column found")
  @ApiResponse(responseCode = "404", description = "Column not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.INT) int id) {
    return columnService.findOneById(id).toJson();
  }

  @PatchMapping("/{id}")
  @Operation(summary = "Update a column")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column updated")
  @ApiResponse(responseCode = "404", description = "Column not found")
  @ApiResponse(responseCode = "409", description = "Column name already exists")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.INT) int id,
      @ValidatedBody UpdateKanbanColumnDto dto) {
    return columnService.update(id, dto).toJson();
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Delete a column")
  @Parameter(name = "id", description = "Column ID")
  @ApiResponse(responseCode = "200", description = "Column deleted")
  @ApiResponse(responseCode = "404", description = "Column not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.INT) int id) {
    columnService.remove(id);
  }
}
