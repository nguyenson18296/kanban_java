package com.kanban.modules.auth.guards;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Nest {@code @UseGuards(JwtAuthGuard)} — opt-in per route or per controller. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface JwtAuth {}
