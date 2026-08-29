package com.kanban.modules.events;

/** Port of @nestjs/websockets WsException. */
public class WsException extends RuntimeException {
  public WsException(String message) {
    super(message);
  }
}
