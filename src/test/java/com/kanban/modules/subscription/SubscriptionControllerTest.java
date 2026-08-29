package com.kanban.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.subscription.dto.SubscriberListResponseDto;
import com.kanban.modules.subscription.dto.SubscriptionStatusDto;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRole;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of subscription.controller.spec.ts */
class SubscriptionControllerTest {
  private SubscriptionService service;
  private SubscriptionController controller;

  @BeforeEach
  void setUp() {
    service = mock(SubscriptionService.class);
    controller = new SubscriptionController(service);
    doNothing().when(service).ensureTaskExists(any());
  }

  @Nested
  class SubscribeManual {
    @Test
    @DisplayName("validates the task, subscribes strictly, and returns status")
    void subscribes() {
      when(service.getMyStatus("t1", "u1"))
          .thenReturn(new SubscriptionStatusDto(true, SubscriptionSource.MANUAL, "2026-07-04T00:00:00.000Z"));
      SubscriptionStatusDto res = controller.subscribe("t1", "u1");
      verify(service).ensureTaskExists("t1");
      verify(service).subscribeStrict("t1", "u1", SubscriptionSource.MANUAL);
      assertThat(res.subscribed()).isTrue();
    }

    @Test
    @DisplayName("propagates a strict subscribe failure (no false 201)")
    void propagates() {
      doThrow(new RuntimeException("db down")).when(service).subscribeStrict(any(), any(), any());
      assertThatThrownBy(() -> controller.subscribe("t1", "u1")).hasMessage("db down");
      verify(service, never()).getMyStatus(any(), any());
    }
  }

  @Nested
  class ListSubscribers {
    @Test
    @DisplayName("404s and does not list when the task is missing")
    void missing() {
      doThrow(new NotFoundException()).when(service).ensureTaskExists("missing");
      assertThatThrownBy(() -> controller.listSubscribers("missing")).isInstanceOf(NotFoundException.class);
      verify(service, never()).listSubscribers(any());
    }

    @Test
    @DisplayName("validates the task then maps subscribers to the response shape")
    void maps() {
      User alice = new User("u1", "alice@example.com", "Alice", UserRole.BACKEND_DEVELOPER, null, true);
      when(service.listSubscribers("t1")).thenReturn(List.of(new TaskSubscription("t1", "u1",
          SubscriptionSource.ASSIGNED, alice, Instant.parse("2026-07-04T00:00:00.000Z"))));
      SubscriberListResponseDto res = controller.listSubscribers("t1");
      verify(service).ensureTaskExists("t1");
      assertThat(res.items()).containsExactly(new SubscriberListResponseDto.SubscriberDto(
          "u1", "Alice", null, SubscriptionSource.ASSIGNED, "2026-07-04T00:00:00.000Z"));
    }
  }

  @Nested
  class GetMyStatus {
    @Test
    @DisplayName("404s and does not read status when the task is missing")
    void missing() {
      doThrow(new NotFoundException()).when(service).ensureTaskExists("missing");
      assertThatThrownBy(() -> controller.getMyStatus("missing", "u1")).isInstanceOf(NotFoundException.class);
      verify(service, never()).getMyStatus(any(), any());
    }
  }
}
