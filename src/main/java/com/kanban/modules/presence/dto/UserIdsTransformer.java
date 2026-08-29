package com.kanban.modules.presence.dto;

import com.kanban.common.validation.ValueTransformer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** array → dedupe; string → split(','), trim, drop empties, dedupe; otherwise unchanged. */
public class UserIdsTransformer implements ValueTransformer {
  @Override
  public Object transform(Object value) {
    if (value instanceof List<?> list) {
      return new ArrayList<>(new LinkedHashSet<>(list));
    }
    if (!(value instanceof String s)) {
      return value;
    }
    LinkedHashSet<String> out = new LinkedHashSet<>();
    for (String part : s.split(",", -1)) {
      String t = part.trim();
      if (!t.isEmpty()) {
        out.add(t);
      }
    }
    return new ArrayList<>(out);
  }
}
