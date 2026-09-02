package com.kanban.modules.subscription.dto;

import com.kanban.modules.subscription.SubscriptionSource;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record SubscriberListResponseDto(List<SubscriberDto> items) {
  public record SubscriberDto(
      @Schema(example = "a1b2c3d4-e5f6-4890-abcd-ef1234567890") String user_id,
      @Schema(example = "Alice Nguyen") String full_name,
      @Schema(nullable = true) String avatar_url,
      SubscriptionSource source,
      @Schema(example = "2026-07-03T00:00:00.000Z") String created_at) {}
}
