package com.kanban.modules.task.dto;

import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsDate;
import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.IsNotEmpty;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.IsString;
import com.kanban.common.validation.IsUUID;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.Min;
import com.kanban.common.validation.TransformWith;
import com.kanban.common.validation.TypeNumber;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.task.TaskPriority;
import com.kanban.modules.task.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

public class CreateTaskDto extends ValidatedDto {
  @Schema(example = "Implement login page")
  @IsString
  @IsNotEmpty
  @MaxLength(255)
  public String title;

  @Schema(example = "Build the login form with validation")
  @IsOptional
  @IsString
  public String description;

  @IsOptional
  @IsEnum(TaskStatus.class)
  public TaskStatus status;

  @IsOptional
  @IsEnum(TaskPriority.class)
  public TaskPriority priority;

  @Schema(example = "1", description = "Kanban column ID")
  @IsInt
  public Integer column_id;

  @Schema(example = "0", description = "Position within the column")
  @IsOptional
  @IsInt
  @Min(0)
  public Integer position;

  @Schema(example = "1")
  @IsOptional
  @TransformWith(TeamIdTransformer.class)
  @IsInt
  public Integer team_id;

  @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890", description = "UUID of the user who created this task")
  @IsOptional
  @IsUUID
  public String created_by;

  @Schema(example = "2025-02-01T00:00:00.000Z")
  @IsOptional
  @TransformWith(DueDateTransformer.class)
  @IsDate
  public Instant due_date;

  @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890", description = "UUID of the parent task (makes this a subtask)")
  @IsOptional
  @IsUUID
  public String parent_id;

  @Schema(description = "Array of user UUIDs to assign")
  @IsOptional
  @IsArray
  @IsUUID(version = "4", each = true)
  public List<String> assignee_ids;

  @Schema(description = "Array of label IDs to attach")
  @IsOptional
  @IsArray
  @TypeNumber
  @IsInt(each = true)
  public List<Integer> label_ids;
}
