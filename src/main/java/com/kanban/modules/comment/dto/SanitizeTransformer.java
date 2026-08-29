package com.kanban.modules.comment.dto;

import com.kanban.common.util.SanitizeHtml;
import com.kanban.common.validation.ValueTransformer;

/** {@code typeof value === 'string' ? sanitize(value) : value} */
public class SanitizeTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    return value instanceof String s ? SanitizeHtml.sanitize(s) : value;
  }
}
