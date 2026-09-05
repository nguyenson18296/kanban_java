package com.kanban.testing;

import com.kanban.common.events.EventBus;
import java.util.ArrayList;
import java.util.List;

/** Test double for the type-based {@link EventBus}: records every emitted event so tests can query by class. */
public class RecordingEventBus implements EventBus {
  public final List<Object> events = new ArrayList<>();

  @Override
  public void emit(Object event) {
    events.add(event);
  }

  /** Every recorded event assignable to {@code eventType}, in emit order. */
  public <T> List<T> emittedOf(Class<T> eventType) {
    return events.stream().filter(eventType::isInstance).map(eventType::cast).toList();
  }

  public void clear() {
    events.clear();
  }
}
