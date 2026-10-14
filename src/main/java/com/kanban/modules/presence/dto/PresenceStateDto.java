package com.kanban.modules.presence.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Known camelCase WS-adjacent surface — kept as-is for wire compatibility. */
public record PresenceStateDto(
    @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890") String userId,
    @Schema(example = "true") boolean isOnline,
    @Schema(example = "1", description = "Live socket count for the user across every backend") int connectionCount,
    @Schema(nullable = true, example = "2026-06-18T15:50:59.391Z",
        description = "ISO timestamp (Redis clock) of the last online↔offline transition; heartbeats never "
            + "change it. null if the user has never connected.") String lastChangedAt) {}
