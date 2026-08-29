package com.kanban.testing;

import com.kanban.common.events.EventBus;
import java.util.ArrayList;
import java.util.List;

/** Test double for EventEmitter2: records every emit so tests can query {@code emittedOf(name)}. */
public class RecordingEventBus implements EventBus {
  public record Emitted(String name, Object payload) {}

  public final List<Emitted> events = new ArrayList<>();

  @Override
  public void emit(String eventName, Object payload) {
    events.add(new Emitted(eventName, payload));
  }

  public List<Object> emittedOf(String name) {
    return events.stream().filter(e -> e.name().equals(name)).map(Emitted::payload).toList();
  }

  public boolean wasEmitted(String name, Object payload) {
    return events.stream().anyMatch(e -> e.name().equals(name) && e.payload().equals(payload));
  }

  public void clear() {
    events.clear();
  }
}
