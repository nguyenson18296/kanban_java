package com.kanban.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.subscription.dto.SubscriptionStatusDto;
import com.kanban.modules.task.TaskRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of subscription.service.spec.ts */
class SubscriptionServiceTest {
  private TaskSubscriptionRepository subRepo;
  private TaskRepository taskRepo;
  private SubscriptionService service;

  @BeforeEach
  void setUp() {
    subRepo = mock(TaskSubscriptionRepository.class);
    taskRepo = mock(TaskRepository.class);
    service = new SubscriptionService(subRepo, taskRepo);
  }

  @Nested
  class Subscribe {
    @Test
    @DisplayName("inserts with orIgnore (idempotent) and the given values")
    void inserts() {
      service.subscribe("t1", "u1", SubscriptionSource.COMMENTED);
      verify(subRepo).insertIgnore("t1", "u1", "commented");
    }

    @Test
    @DisplayName("is resilient: swallows repo errors and does not throw")
    void resilient() {
      when(subRepo.insertIgnore(any(), any(), any())).thenThrow(new RuntimeException("db down"));
      assertThatCode(() -> service.subscribe("t1", "u1", SubscriptionSource.MANUAL)).doesNotThrowAnyException();
    }
  }

  @Nested
  class SubscribeStrict {
    @Test
    @DisplayName("inserts idempotently (orIgnore) like subscribe")
    void inserts() {
      service.subscribeStrict("t1", "u1", SubscriptionSource.MANUAL);
      verify(subRepo).insertIgnore("t1", "u1", "manual");
    }

    @Test
    @DisplayName("rethrows on repo failure (no false success for the manual endpoint)")
    void rethrows() {
      when(subRepo.insertIgnore(any(), any(), any())).thenThrow(new RuntimeException("db down"));
      assertThatThrownBy(() -> service.subscribeStrict("t1", "u1", SubscriptionSource.MANUAL))
          .hasMessage("db down");
    }
  }

  @Nested
  class SubscribeMany {
    @Test
    @DisplayName("no-ops on an empty list (no query issued)")
    void emptyList() {
      service.subscribeMany("t1", List.of(), SubscriptionSource.ASSIGNED);
      verifyNoInteractions(subRepo);
    }

    @Test
    @DisplayName("dedupes ids and batch-inserts")
    void dedupes() {
      service.subscribeMany("t1", List.of("u1", "u1", "u2"), SubscriptionSource.ASSIGNED);
      verify(subRepo, times(1)).insertIgnore("t1", "u1", "assigned");
      verify(subRepo, times(1)).insertIgnore("t1", "u2", "assigned");
      verify(subRepo, times(2)).insertIgnore(any(), any(), any());
    }
  }

  @Test
  @DisplayName("unsubscribe deletes the composite-keyed row")
  void unsubscribe() {
    service.unsubscribe("t1", "u1");
    verify(subRepo).deleteByTaskIdAndUserId("t1", "u1");
  }

  @Nested
  class GetMyStatus {
    @Test
    @DisplayName("reports subscribed with source and since")
    void subscribed() {
      when(subRepo.findByTaskIdAndUserId("t1", "u1")).thenReturn(Optional.of(new TaskSubscription("t1", "u1",
          SubscriptionSource.MANUAL, null, Instant.parse("2026-07-03T00:00:00.000Z"))));
      assertThat(service.getMyStatus("t1", "u1"))
          .isEqualTo(new SubscriptionStatusDto(true, SubscriptionSource.MANUAL, "2026-07-03T00:00:00.000Z"));
    }

    @Test
    @DisplayName("reports not subscribed when no row exists")
    void notSubscribed() {
      when(subRepo.findByTaskIdAndUserId("t1", "u1")).thenReturn(Optional.empty());
      assertThat(service.getMyStatus("t1", "u1")).isEqualTo(new SubscriptionStatusDto(false, null, null));
    }
  }

  @Nested
  class GetSubscriberIds {
    @Test
    @DisplayName("maps rows to user ids")
    void maps() {
      when(subRepo.findUserIdsByTaskId("t1")).thenReturn(List.of("u1", "u2"));
      assertThat(service.getSubscriberIds("t1")).containsExactly("u1", "u2");
    }

    @Test
    @DisplayName("is resilient: returns [] on repo error")
    void resilient() {
      when(subRepo.findUserIdsByTaskId("t1")).thenThrow(new RuntimeException("db down"));
      assertThat(service.getSubscriberIds("t1")).isEmpty();
    }
  }

  @Nested
  class EnsureTaskExists {
    @Test
    @DisplayName("throws NotFoundException when the task is missing")
    void missing() {
      when(taskRepo.existsById("missing")).thenReturn(false);
      assertThatThrownBy(() -> service.ensureTaskExists("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("resolves when the task exists")
    void exists() {
      when(taskRepo.existsById("t1")).thenReturn(true);
      assertThatCode(() -> service.ensureTaskExists("t1")).doesNotThrowAnyException();
    }
  }
}
