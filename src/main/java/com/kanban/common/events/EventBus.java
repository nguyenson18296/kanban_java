package com.kanban.common.events;

/** Type-based event dispatch: listeners subscribe by the payload's type via Spring {@code @EventListener}. */
public interface EventBus {
  void emit(Object event);
}
