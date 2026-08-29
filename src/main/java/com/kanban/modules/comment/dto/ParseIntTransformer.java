package com.kanban.modules.comment.dto;

import com.kanban.common.validation.JsValues;
import com.kanban.common.validation.ValueTransformer;

/** {@code Number.parseInt(value, 10)} (always applied — CommentQueryDto). */
public class ParseIntTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    return JsValues.jsParseInt(value);
  }
}
