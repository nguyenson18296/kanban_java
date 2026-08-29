package com.kanban.common.events;

/** Port of Nest's {@code EventEmitter2.emit(name, payload)}; listeners subscribe by payload type. */
public interface EventBus {
  void emit(String eventName, Object payload);
}
