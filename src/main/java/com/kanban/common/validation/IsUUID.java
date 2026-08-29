package com.kanban.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** class-validator {@code @IsUUID(version, { each })} equivalent. {@code version} = "all" | "4". */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsUUID {
  String version() default "all";

  boolean each() default false;
}
