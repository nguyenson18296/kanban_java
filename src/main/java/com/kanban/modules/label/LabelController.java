package com.kanban.modules.label;

import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.label.dto.CreateLabelDto;
import com.kanban.modules.label.dto.UpdateLabelDto;
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

@Tag(name = "Labels")
@RestController
@RequestMapping("/labels")
public class LabelController {
  private final LabelService labelService;

  public LabelController(LabelService labelService) {
    this.labelService = labelService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create a label")
  @ApiResponse(responseCode = "201", description = "Label created")
  @ApiResponse(responseCode = "409", description = "Label name already exists")
  public Map<String, Object> create(@ValidatedBody CreateLabelDto dto) {
    return labelService.create(dto).toJson();
  }

  @GetMapping
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get all labels")
  @ApiResponse(responseCode = "200", description = "List of labels")
  public List<Map<String, Object>> findAll() {
    return labelService.findAll().stream().map(Label::toJson).toList();
  }

  @GetMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Get a label by ID")
  @Parameter(name = "id", description = "Label ID")
  @ApiResponse(responseCode = "200", description = "Label found")
  @ApiResponse(responseCode = "404", description = "Label not found")
  public Map<String, Object> findOne(@Param(value = "id", pipe = Param.Pipe.INT) int id) {
    return labelService.findOneById(id).toJson();
  }

  @PatchMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Update a label")
  @Parameter(name = "id", description = "Label ID")
  @ApiResponse(responseCode = "200", description = "Label updated")
  @ApiResponse(responseCode = "404", description = "Label not found")
  @ApiResponse(responseCode = "409", description = "Label name already exists")
  public Map<String, Object> update(@Param(value = "id", pipe = Param.Pipe.INT) int id,
      @ValidatedBody UpdateLabelDto dto) {
    return labelService.update(id, dto).toJson();
  }

  @DeleteMapping("/{id}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Delete a label")
  @Parameter(name = "id", description = "Label ID")
  @ApiResponse(responseCode = "200", description = "Label deleted")
  @ApiResponse(responseCode = "404", description = "Label not found")
  public void remove(@Param(value = "id", pipe = Param.Pipe.INT) int id) {
    labelService.remove(id);
  }
}
