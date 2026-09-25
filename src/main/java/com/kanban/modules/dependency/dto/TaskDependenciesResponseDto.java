package com.kanban.modules.dependency.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record TaskDependenciesResponseDto(
    @Schema(description = "Tasks that block this task") List<TaskSummaryDto> blocked_by,
    @Schema(description = "Tasks this task blocks") List<TaskSummaryDto> blocks) {}
