package com.kanban.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** class-validator {@code @Matches(regex, { message })} equivalent. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Matches {
  String value();

  /** Custom message; empty → default "$property must match $regex regular expression". */
  String message() default "";
}
