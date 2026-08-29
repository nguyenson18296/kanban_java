package com.kanban.modules.events.socket;

import java.util.List;

/** Minimal Socket.IO server surface (mirrors {@code server.to(room).emit} and {@code server.in(id).socketsJoin}). */
public interface SocketServer {
  /** {@code server.to(room).emit(event, data)} */
  void emitToRoom(String room, String event, Object data);

  /** {@code server.in(socketId).socketsJoin(rooms)} */
  void joinRooms(String socketId, List<String> rooms);
}
