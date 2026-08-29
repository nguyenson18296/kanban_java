package com.kanban.modules.events.socket;

import java.util.Map;

/** Minimal Socket.IO client surface the gateway needs (mirrors socket.io's {@code Socket}). */
public interface SocketClient {
  /** {@code client.id} */
  String getId();

  /** {@code client.data} — mutable per-connection bag (holds the authenticated user). */
  Map<String, Object> data();

  /** {@code client.handshake} */
  Handshake handshake();

  /** {@code client.join(room)} */
  void join(String room);

  /** {@code client.emit(event, data)} */
  void emit(String event, Object data);

  /** {@code client.disconnect(close)} */
  void disconnect(boolean close);

  interface Handshake {
    /** {@code handshake.auth} — may be null when the client sent no auth payload. */
    Map<String, Object> auth();

    void setAuth(Map<String, Object> auth);

    /** {@code handshake.headers[name]} (case-insensitive), or null. */
    String header(String name);
  }
}
