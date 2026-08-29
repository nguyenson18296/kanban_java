package com.kanban.modules.common;

import com.kanban.common.validation.WireEnum;
import jakarta.persistence.AttributeConverter;

/** Maps a {@link WireEnum} to its snake_case DB value (Postgres enum types accept the text). */
public abstract class WireEnumConverter<E extends Enum<E> & WireEnum> implements AttributeConverter<E, String> {
  private final Class<E> type;

  protected WireEnumConverter(Class<E> type) {
    this.type = type;
  }

  @Override
  public String convertToDatabaseColumn(E attribute) {
    return attribute == null ? null : attribute.value();
  }

  @Override
  public E convertToEntityAttribute(String dbData) {
    if (dbData == null) {
      return null;
    }
    E e = WireEnum.fromValue(type, dbData);
    if (e == null) {
      throw new IllegalArgumentException("Unknown " + type.getSimpleName() + " value: " + dbData);
    }
    return e;
  }
}
