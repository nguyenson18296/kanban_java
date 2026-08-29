package com.kanban.modules.auth.decorators;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Nest {@code @CurrentUser()} / {@code @CurrentUser('id')}. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface CurrentUser {
  /** Property to pluck ("id") or empty for the whole user. */
  String value() default "";
}
