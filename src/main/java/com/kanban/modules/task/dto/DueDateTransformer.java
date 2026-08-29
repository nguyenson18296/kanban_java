package com.kanban.modules.task.dto;

import com.kanban.common.validation.JsValues;
import com.kanban.common.validation.ValueTransformer;

/** {@code undefined → undefined; '' | null → null; else new Date(value)}. */
public class DueDateTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    if (value == null || "".equals(value)) {
      return null;
    }
    return JsValues.jsDate(value);
  }
}
