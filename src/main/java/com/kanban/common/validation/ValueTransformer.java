package com.kanban.common.validation;

/** A class-transformer style value transform. Implementations need a public no-arg constructor. */
public interface ValueTransformer {
  Object transform(Object value);
}
