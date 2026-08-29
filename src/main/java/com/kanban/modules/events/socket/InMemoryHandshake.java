package com.kanban.modules.events.socket;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Simple {@link SocketClient.Handshake} backed by maps (used by the netty adapter and tests). */
public class InMemoryHandshake implements SocketClient.Handshake {
  private Map<String, Object> auth;
  private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  public InMemoryHandshake(Map<String, Object> auth, Map<String, String> headers) {
    this.auth = auth;
    if (headers != null) {
      this.headers.putAll(headers);
    }
  }

  @Override
  public Map<String, Object> auth() {
    return auth;
  }

  @Override
  public void setAuth(Map<String, Object> auth) {
    this.auth = auth;
  }

  @Override
  public String header(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }
}
