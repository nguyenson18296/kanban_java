package com.kanban.modules.dashboard;

import com.kanban.modules.dashboard.DashboardQueries.AssigneeRow;
import com.kanban.modules.dashboard.DashboardQueries.Snapshot;
import com.kanban.modules.dashboard.DashboardQueries.StatusRow;
import com.kanban.modules.dashboard.dto.DashboardResponse;
import com.kanban.modules.dashboard.dto.DashboardResponse.AssigneeCount;
import com.kanban.modules.dashboard.dto.DashboardResponse.ByStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Project dashboard statistics (JSP-44), cache-aside. Access is checked before this runs, on every request
 * and before any cache read, by {@code @RequireProjectRole(VIEWER)} on the controller.
 */
@Service
public class DashboardService {
  private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

  private final DashboardQueries queries;
  private final DashboardCache cache;

  public DashboardService(DashboardQueries queries, DashboardCache cache) {
    this.queries = queries;
    this.cache = cache;
  }

  public DashboardResponse get(String projectId) {
    DashboardCache.Lookup cached = cache.get(projectId);
    if (cached instanceof DashboardCache.Hit hit) {
      return hit.value();
    }
    long started = System.nanoTime();
    // Millisecond precision: the overdue cut-off is exactly the computed_at the client sees.
    Instant asOf = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    DashboardResponse fresh = toResponse(projectId, asOf, queries.snapshot(projectId, asOf));
    log.debug("Dashboard for project {} computed in {} ms", projectId, (System.nanoTime() - started) / 1_000_000);
    // Store only after a clean miss: when Redis just failed, do not wait on it a second time.
    if (cached instanceof DashboardCache.Miss) {
      cache.put(projectId, fresh);
    }
    return fresh;
  }

  private static DashboardResponse toResponse(String projectId, Instant asOf, Snapshot snapshot) {
    long open = 0;
    long inProgress = 0;
    long inReview = 0;
    long done = 0;
    long cancelled = 0;
    long total = 0;
    long overdue = 0;
    long unassigned = 0;
    for (StatusRow row : snapshot.statuses()) {
      switch (row.status()) {
        case OPEN -> open = row.tasks();
        case IN_PROGRESS -> inProgress = row.tasks();
        case IN_REVIEW -> inReview = row.tasks();
        case DONE -> done = row.tasks();
        case CANCELLED -> cancelled = row.tasks();
      }
      total += row.tasks();
      overdue += row.overdue();
      unassigned += row.unassigned();
    }
    List<AssigneeCount> byAssignee = snapshot.assignees().stream()
        .sorted(Comparator.comparingLong(AssigneeRow::tasks).reversed().thenComparing(AssigneeRow::userId))
        .map(row -> new AssigneeCount(row.userId(), row.tasks()))
        .toList();
    return new DashboardResponse(projectId, total, overdue,
        new ByStatus(open, inProgress, inReview, done, cancelled), byAssignee, unassigned, asOf);
  }
}
