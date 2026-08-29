package com.kanban.modules.presence.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Known camelCase WS-adjacent surface — kept as-is for wire compatibility. */
public record PresenceStateDto(
    @Schema(example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") String userId,
    @Schema(example = "true") boolean isOnline,
    @Schema(example = "1", description = "Active socket count for the user") int connectionCount,
    @Schema(nullable = true, example = "2026-06-18T15:50:59.391Z",
        description = "ISO timestamp of the last online↔offline transition during current server uptime; "
            + "null if the user has never connected.") String lastChangedAt) {}
