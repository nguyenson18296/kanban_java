package com.kanban.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * class-validator {@code @IsIn(values)} equivalent — restricts a string-backed
 * (including wire-string-backed enum) field to an explicit allow-list, unlike
 * {@link IsEnum} which accepts every value of the enum.
 *
 * <p>The literal {@code value()} allow-list is not derived from the enum, so it
 * must be kept in sync by hand with the referenced enum's wire values (e.g.
 * {@code CreateInvitationDto.role}'s {@code {"admin", "member", "viewer"}}
 * against {@code ProjectRole}'s wire values) whenever that enum changes.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsIn {
  String[] value();
}
