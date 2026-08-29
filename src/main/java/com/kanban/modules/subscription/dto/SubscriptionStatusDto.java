package com.kanban.modules.subscription.dto;

import com.kanban.modules.subscription.SubscriptionSource;
import io.swagger.v3.oas.annotations.media.Schema;

public record SubscriptionStatusDto(
    @Schema(example = "true") boolean subscribed,
    @Schema(nullable = true) SubscriptionSource source,
    @Schema(nullable = true, example = "2026-07-03T00:00:00.000Z", description = "When the subscription was created")
    String since) {}
