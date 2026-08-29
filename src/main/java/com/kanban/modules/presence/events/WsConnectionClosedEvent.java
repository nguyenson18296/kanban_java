package com.kanban.modules.presence.events;

public record WsConnectionClosedEvent(String userId, String socketId) {}
