package com.kanban.common.json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small helper for building ordered JSON-like maps (mirrors JS object literals). */
public final class Json {
  private Json() {}

  /** {@code Json.map("a", 1, "b", 2)} → ordered map. Null values are kept (JSON null). */
  public static Map<String, Object> map(Object... kv) {
    if (kv.length % 2 != 0) {
      throw new IllegalArgumentException("Json.map requires key/value pairs");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      out.put(String.valueOf(kv[i]), kv[i + 1]);
    }
    return out;
  }
}
