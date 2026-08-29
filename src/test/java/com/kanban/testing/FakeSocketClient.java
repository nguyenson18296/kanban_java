package com.kanban.testing;

import com.kanban.modules.events.socket.InMemoryHandshake;
import com.kanban.modules.events.socket.SocketClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Recording Socket.IO client (the jest {@code client} mock). */
public class FakeSocketClient implements SocketClient {
  public record EmittedEvent(String event, Object data) {}

  private final String id;
  private final Map<String, Object> data = new HashMap<>();
  private final InMemoryHandshake handshake;
  public final List<String> joined = new ArrayList<>();
  public final List<EmittedEvent> emitted = new ArrayList<>();
  public final List<Boolean> disconnects = new ArrayList<>();

  public FakeSocketClient(String id, Map<String, Object> auth) {
    this.id = id;
    this.handshake = new InMemoryHandshake(auth, Map.of());
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public Map<String, Object> data() {
    return data;
  }

  @Override
  public Handshake handshake() {
    return handshake;
  }

  @Override
  public void join(String room) {
    joined.add(room);
  }

  @Override
  public void emit(String event, Object payload) {
    emitted.add(new EmittedEvent(event, payload));
  }

  @Override
  public void disconnect(boolean close) {
    disconnects.add(close);
  }

  public boolean emittedEvent(String event, Object payload) {
    return emitted.stream().anyMatch(e -> e.event().equals(event) && e.data().equals(payload));
  }
}
