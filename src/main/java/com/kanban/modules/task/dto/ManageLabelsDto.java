package com.kanban.modules.task.dto;

import com.kanban.common.validation.ArrayNotEmpty;
import com.kanban.common.validation.IsArray;
import com.kanban.common.validation.IsInt;
import com.kanban.common.validation.TypeNumber;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public class ManageLabelsDto extends ValidatedDto {
  @Schema(description = "Array of label IDs")
  @IsArray
  @ArrayNotEmpty
  @TypeNumber
  @IsInt(each = true)
  public List<Integer> label_ids;
}
