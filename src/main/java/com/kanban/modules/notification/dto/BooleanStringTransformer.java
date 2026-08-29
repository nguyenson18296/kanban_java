package com.kanban.modules.notification.dto;

import com.kanban.common.validation.ValueTransformer;

/** {@code 'true' → true, 'false' → false, otherwise unchanged}. */
public class BooleanStringTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    if ("true".equals(value)) {
      return Boolean.TRUE;
    }
    if ("false".equals(value)) {
      return Boolean.FALSE;
    }
    return value;
  }
}
