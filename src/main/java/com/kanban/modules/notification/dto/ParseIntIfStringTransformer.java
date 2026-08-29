package com.kanban.modules.notification.dto;

import com.kanban.common.validation.JsValues;
import com.kanban.common.validation.ValueTransformer;

/** {@code typeof value === 'string' ? Number.parseInt(value, 10) : value} */
public class ParseIntIfStringTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    return value instanceof String ? JsValues.jsParseInt(value) : value;
  }
}
