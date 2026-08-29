package com.kanban.modules.task.dto;

import com.kanban.common.validation.JsValues;
import com.kanban.common.validation.ValueTransformer;

/** {@code value === 0 ? undefined : value}. */
public class TeamIdTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    if (value instanceof Number n && n.doubleValue() == 0d) {
      return JsValues.UNDEFINED;
    }
    return value;
  }
}
