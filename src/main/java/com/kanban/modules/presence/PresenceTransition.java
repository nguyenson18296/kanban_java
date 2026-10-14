package com.kanban.modules.presence;

import java.time.Instant;

/** An online/offline change that {@link RedisPresenceStore} applied; {@code changedAt} is Redis's clock. */
public record PresenceTransition(String userId, boolean online, int connectionCount, Instant changedAt) {}
