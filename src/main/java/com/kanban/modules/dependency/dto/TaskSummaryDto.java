package com.kanban.modules.dependency.dto;

import com.kanban.modules.task.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The compact task shape both dependency directions are rendered with. */
public record TaskSummaryDto(
    @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890") String id,
    @Schema(example = "KAN-12") String ticket_id,
    @Schema(example = "Design schema") String title,
    TaskStatus status,
    @Schema(example = "3") Integer column_id) {}
