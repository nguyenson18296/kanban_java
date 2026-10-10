package com.kanban.modules.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.modules.dashboard.DashboardQueries.AssigneeRow;
import com.kanban.modules.dashboard.DashboardQueries.Snapshot;
import com.kanban.modules.dashboard.DashboardQueries.StatusRow;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import com.kanban.modules.dashboard.dto.DashboardResponse.AssigneeCount;
import com.kanban.modules.dashboard.dto.DashboardResponse.ByStatus;
import com.kanban.modules.task.TaskStatus;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** JSP-44: the project dashboard response — cache-aside flow and how it is built from the aggregate rows. */
class DashboardServiceTest {
  private static final String PROJECT = "UrzWUH3e";

  private DashboardQueries queries;
  private DashboardCache cache;
  private DashboardService service;

  @BeforeEach
  void setUp() {
    queries = mock(DashboardQueries.class);
    cache = mock(DashboardCache.class);
    when(cache.get(PROJECT)).thenReturn(DashboardCache.MISS);
    service = new DashboardService(queries, cache);
  }

  private void rows(List<StatusRow> statuses, List<AssigneeRow> assignees) {
    when(queries.snapshot(eq(PROJECT), any())).thenReturn(new Snapshot(statuses, assignees));
  }

  @Test
  @DisplayName("cache hit → returns the cached snapshot (its computed_at) and runs no aggregation")
  void hitSkipsAggregation() {
    DashboardResponse cached = new DashboardResponse(PROJECT, 1, 0, new ByStatus(1, 0, 0, 0, 0), List.of(), 1,
        Instant.parse("2026-10-06T07:00:00Z"));
    when(cache.get(PROJECT)).thenReturn(new DashboardCache.Hit(cached));

    assertThat(service.get(PROJECT)).isSameAs(cached);
    verifyNoInteractions(queries);
    verify(cache, never()).put(any(), any());
  }

  @Test
  @DisplayName("cache miss → runs the aggregation once and stores the result")
  void missComputesAndStores() {
    rows(List.of(new StatusRow(TaskStatus.OPEN, 1, 0, 1)), List.of());

    DashboardResponse result = service.get(PROJECT);

    verify(queries, times(1)).snapshot(eq(PROJECT), any());
    verify(cache).put(PROJECT, result);
  }

  @Test
  @DisplayName("Redis unavailable (SKIP) → still answers from PostgreSQL, without a second Redis call to store")
  void skipComputesWithoutStoring() {
    when(cache.get(PROJECT)).thenReturn(DashboardCache.SKIP);
    rows(List.of(new StatusRow(TaskStatus.OPEN, 1, 0, 1)), List.of());

    assertThat(service.get(PROJECT).total_tasks()).isEqualTo(1);
    verify(cache, never()).put(any(), any());
  }

  @Test
  @DisplayName("fills 0 for statuses without tasks and sums totals, overdue and unassigned across statuses")
  void fillsZerosAndSums() {
    rows(List.of(
        new StatusRow(TaskStatus.OPEN, 5, 2, 3),
        new StatusRow(TaskStatus.DONE, 4, 0, 1)), List.of());

    DashboardResponse result = service.get(PROJECT);

    assertThat(result.project_id()).isEqualTo(PROJECT);
    assertThat(result.by_status()).isEqualTo(new ByStatus(5, 0, 0, 4, 0));
    assertThat(result.total_tasks()).isEqualTo(9);
    assertThat(result.overdue_tasks()).isEqualTo(2);
    assertThat(result.unassigned_tasks()).isEqualTo(4);
  }

  @Test
  @DisplayName("orders assignees by task count (most first), ties by user id")
  void ordersAssignees() {
    rows(List.of(new StatusRow(TaskStatus.OPEN, 3, 0, 0)), List.of(
        new AssigneeRow("b", 1),
        new AssigneeRow("c", 3),
        new AssigneeRow("a", 1)));

    assertThat(service.get(PROJECT).by_assignee()).containsExactly(
        new AssigneeCount("c", 3),
        new AssigneeCount("a", 1),
        new AssigneeCount("b", 1));
  }

  @Test
  @DisplayName("computed_at is the millisecond cut-off the query used for overdue")
  void computedAtIsTheQueryCutOff() {
    rows(List.of(), List.of());

    DashboardResponse result = service.get(PROJECT);

    ArgumentCaptor<Instant> asOf = ArgumentCaptor.forClass(Instant.class);
    verify(queries).snapshot(eq(PROJECT), asOf.capture());
    assertThat(result.computed_at()).isEqualTo(asOf.getValue());
    assertThat(asOf.getValue()).isEqualTo(asOf.getValue().truncatedTo(ChronoUnit.MILLIS));
  }

  @Test
  @DisplayName("a project without countable tasks returns zeros and an empty assignee list, not an error")
  void emptyProject() {
    rows(List.of(), List.of());

    DashboardResponse result = service.get(PROJECT);

    assertThat(result.total_tasks()).isZero();
    assertThat(result.overdue_tasks()).isZero();
    assertThat(result.unassigned_tasks()).isZero();
    assertThat(result.by_status()).isEqualTo(new ByStatus(0, 0, 0, 0, 0));
    assertThat(result.by_assignee()).isEmpty();
  }

  @Test
  @DisplayName("by_status has exactly one field per TaskStatus wire value (a new status must be added here)")
  void byStatusCoversEveryStatus() {
    assertThat(Arrays.stream(ByStatus.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactlyElementsOf(Arrays.stream(TaskStatus.values()).map(TaskStatus::value).toList());
  }
}
