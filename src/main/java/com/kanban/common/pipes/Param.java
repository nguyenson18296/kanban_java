package com.kanban.common.pipes;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Nest {@code @Param(name, Pipe)} — path variable with an optional parse pipe. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Param {
  String value();

  Pipe pipe() default Pipe.NONE;

  enum Pipe {
    NONE,
    /** Nest ParseUUIDPipe (any UUID shape). */
    UUID,
    /** Nest ParseIntPipe. */
    INT,
    /** Project-specific ParseProjectIdPipe (8 alphanumeric chars). */
    PROJECT_ID
  }
}
