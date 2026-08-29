package com.kanban.modules.presence.events;

public record WsConnectionOpenedEvent(String userId, String socketId) {}
