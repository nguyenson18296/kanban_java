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

/** OmitType(CreateTaskDto, ['parent_id', 'column_id']) + optional column_id. */
public class CreateSubtaskDto extends ValidatedDto {
  @Schema(example = "Implement login page")
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
  @IsArray
  @IsUUID(version = "4", each = true)
  public List<String> assignee_ids;

  @IsOptional
  @IsArray
  @TypeNumber
  @IsInt(each = true)
  public List<Integer> label_ids;

  @Schema(example = "1", description = "Kanban column ID (defaults to parent's column if omitted)")
  @IsOptional
  @IsInt
  public Integer column_id;

  /** Build the CreateTaskDto the service forwards to {@code create()}. */
  public CreateTaskDto toCreateTaskDto(int columnId, String parentId) {
    CreateTaskDto dto = new CreateTaskDto();
    copy(dto, "title", title);
    copy(dto, "description", description);
    copy(dto, "status", status);
    copy(dto, "priority", priority);
    copy(dto, "position", position);
    copy(dto, "team_id", team_id);
    copy(dto, "created_by", created_by);
    copy(dto, "due_date", due_date);
    copy(dto, "assignee_ids", assignee_ids);
    copy(dto, "label_ids", label_ids);
    dto.title = title;
    dto.description = description;
    dto.status = status;
    dto.priority = priority;
    dto.position = position;
    dto.team_id = team_id;
    dto.created_by = created_by;
    dto.due_date = due_date;
    dto.assignee_ids = assignee_ids;
    dto.label_ids = label_ids;
    dto.column_id = columnId;
    dto.with("column_id");
    dto.parent_id = parentId;
    dto.with("parent_id");
    return dto;
  }

  private void copy(CreateTaskDto dto, String key, Object value) {
    if (has(key)) {
      dto.with(key);
    }
  }
}
