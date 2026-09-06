package com.kanban.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.modules.notification.events.BaseNotificationEvent;
import com.kanban.modules.notification.events.TaskAssignedEvent;
import com.kanban.modules.presence.events.WsConnectionOpenedEvent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Proves RecordingEventBus records emitted events and filters them by Java type (assignable, not exact class). */
class RecordingEventBusTest {
  private final RecordingEventBus bus = new RecordingEventBus();

  private static TaskAssignedEvent taskAssigned(String entityId) {
    return new TaskAssignedEvent("actor", entityId, List.of("u1"), Map.of());
  }

  @Test
  @DisplayName("emittedOf returns only events of the requested type, in emit order")
  void filtersByType() {
    WsConnectionOpenedEvent open1 = new WsConnectionOpenedEvent("user-1", "socket-1");
    TaskAssignedEvent assigned = taskAssigned("t1");
    WsConnectionOpenedEvent open2 = new WsConnectionOpenedEvent("user-2", "socket-2");
    bus.emit(open1);
    bus.emit(assigned);
    bus.emit(open2);

    assertThat(bus.emittedOf(WsConnectionOpenedEvent.class)).containsExactly(open1, open2);
    assertThat(bus.emittedOf(TaskAssignedEvent.class)).containsExactly(assigned);
  }

  @Test
  @DisplayName("emittedOf matches by assignability (supertype/interface), not exact class")
  void filtersByAssignableType() {
    TaskAssignedEvent assigned = taskAssigned("t1");
    bus.emit(assigned);
    bus.emit(new WsConnectionOpenedEvent("user-1", "socket-1"));

    // TaskAssignedEvent implements BaseNotificationEvent; the WS event does not.
    assertThat(bus.emittedOf(BaseNotificationEvent.class)).containsExactly(assigned);
    assertThat(bus.emittedOf(Object.class)).hasSize(2);
  }

  @Test
  @DisplayName("emittedOf is empty when nothing matches; clear() drops recorded events")
  void emptyAndClear() {
    bus.emit(new WsConnectionOpenedEvent("user-1", "socket-1"));
    assertThat(bus.emittedOf(TaskAssignedEvent.class)).isEmpty();

    bus.clear();
    assertThat(bus.emittedOf(WsConnectionOpenedEvent.class)).isEmpty();
    assertThat(bus.events).isEmpty();
  }
}
