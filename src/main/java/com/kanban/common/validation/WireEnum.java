package com.kanban.common.validation;

/** Enums with a snake_case wire value (TypeScript string enums). */
public interface WireEnum {
  String value();

  static <E extends Enum<E> & WireEnum> E fromValue(Class<E> type, Object raw) {
    if (raw == null) {
      return null;
    }
    for (E e : type.getEnumConstants()) {
      if (e.value().equals(raw)) {
        return e;
      }
    }
    return null;
  }
}
