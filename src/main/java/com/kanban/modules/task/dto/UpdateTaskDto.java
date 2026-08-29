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

/** PartialType(CreateTaskDto) */
public class UpdateTaskDto extends ValidatedDto {
  @Schema(example = "Implement login page")
  @IsOptional
  @IsString
  @IsNotEmpty
  @MaxLength(255)
  public String title;

  @IsOptional
  @IsString
  public String description;

  @IsOptional
  @IsEnum(TaskStatus.class)
  public TaskStatus status;

  @IsOptional
  @IsEnum(TaskPriority.class)
  public TaskPriority priority;

  @IsOptional
  @IsInt
  public Integer column_id;

  @IsOptional
  @IsInt
  @Min(0)
  public Integer position;

  @IsOptional
  @TransformWith(TeamIdTransformer.class)
  @IsInt
  public Integer team_id;

  @IsOptional
  @IsUUID
  public String created_by;

  @IsOptional
  @TransformWith(DueDateTransformer.class)
  @IsDate
  public Instant due_date;

  @IsOptional
  @IsUUID
  public String parent_id;

  @IsOptional
  @IsArray
  @IsUUID(version = "4", each = true)
  public List<String> assignee_ids;

  @IsOptional
  @IsArray
  @TypeNumber
  @IsInt(each = true)
  public List<Integer> label_ids;
}
