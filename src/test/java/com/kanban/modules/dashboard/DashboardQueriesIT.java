package com.kanban.modules.dashboard;

import static com.kanban.modules.task.TaskStatus.CANCELLED;
import static com.kanban.modules.task.TaskStatus.DONE;
import static com.kanban.modules.task.TaskStatus.IN_PROGRESS;
import static com.kanban.modules.task.TaskStatus.IN_REVIEW;
import static com.kanban.modules.task.TaskStatus.OPEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.kanban.modules.dashboard.DashboardQueries.AssigneeRow;
import com.kanban.modules.dashboard.DashboardQueries.Snapshot;
import com.kanban.modules.dashboard.DashboardQueries.StatusRow;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JSP-44: the dashboard aggregate against a real PostgreSQL ({@code compose.postgres.yml}), with a fixed
 * cut-off so the overdue boundary is exact. The JVM runs in Asia/Ho_Chi_Minh (dashboard-it profile), so a
 * timestamp-vs-timestamptz binding mistake shows up as a 7-hour shift.
 */
class DashboardQueriesIT {
  private static final Instant AS_OF = Instant.parse("2026-10-06T05:00:00.000Z");
  private static ConfigurableApplicationContext app;
  private static DashboardQueries queries;
  private static DashboardFixtures fx;

  @BeforeAll
  static void start() {
    app = DashboardItApp.start(DashboardItApp.keyPrefix(), Duration.ofSeconds(60), DashboardItApp.REDIS_PORT);
    queries = app.getBean(DashboardQueries.class);
    fx = new DashboardFixtures(app.getBean(JdbcTemplate.class));
  }

  @AfterAll
  static void stop() {
    if (app != null) {
      app.close();
    }
  }

  @Test
  @DisplayName("counts each task and subtask of non-archived columns once; one row per assignee pair")
  void countsTasksSubtasksAndAssignees() {
    String owner = fx.user();
    String alice = fx.user();
    String bob = fx.user();
    String project = fx.project(owner);
    int todo = fx.column(project, false);
    int doing = fx.column(project, false);
    int archived = fx.column(project, true);
    String parent = fx.task(todo, "open", null, alice, bob); // two assignees, still one task
    fx.subtask(parent, doing, "in_progress", null, alice);   // a subtask is its own unit
    fx.task(doing, "in_review", null);                       // unassigned
    fx.task(todo, "done", null, bob);
    fx.task(archived, "open", null, alice);                  // archived column: not counted
    fx.subtask(parent, archived, "open", null, bob);         // subtask in an archived column: not counted
    String other = fx.project(owner);
    fx.task(fx.column(other, false), "open", null, alice);   // another project: not counted

    Snapshot snapshot = queries.snapshot(project, AS_OF);

    assertThat(snapshot.statuses()).containsExactlyInAnyOrder(
        new StatusRow(OPEN, 1, 0, 0),
        new StatusRow(IN_PROGRESS, 1, 0, 0),
        new StatusRow(IN_REVIEW, 1, 0, 1),
        new StatusRow(DONE, 1, 0, 0));
    assertThat(snapshot.assignees()).containsExactlyInAnyOrder(
        new AssigneeRow(alice, 2),
        new AssigneeRow(bob, 2));
  }

  @Test
  @DisplayName("overdue means due_date strictly before asOf and not done/cancelled; a null due_date never is")
  void overdueBoundaries() {
    String owner = fx.user();
    String project = fx.project(owner);
    int column = fx.column(project, false);
    fx.task(column, "open", AS_OF.minusMillis(1));             // overdue
    fx.task(column, "open", AS_OF);                            // due exactly at the cut-off: not overdue
    fx.task(column, "open", AS_OF.plusMillis(1));              // not yet due
    fx.task(column, "open", null);                             // no due date
    fx.task(column, "in_progress", AS_OF.minus(Duration.ofDays(30))); // overdue
    fx.task(column, "done", AS_OF.minus(Duration.ofDays(1)));         // finished: never overdue
    fx.task(column, "cancelled", AS_OF.minus(Duration.ofDays(1)));    // finished: never overdue

    Snapshot snapshot = queries.snapshot(project, AS_OF);

    assertThat(snapshot.statuses()).containsExactlyInAnyOrder(
        new StatusRow(OPEN, 4, 1, 4),
        new StatusRow(IN_PROGRESS, 1, 1, 1),
        new StatusRow(DONE, 1, 0, 1),
        new StatusRow(CANCELLED, 1, 0, 1));
    assertThat(snapshot.assignees()).isEmpty();
  }

  @Test
  @DisplayName("a project with no countable task returns no rows (the service fills the zeros)")
  void emptyProject() {
    String owner = fx.user();
    String project = fx.project(owner);
    fx.column(project, false);
    fx.task(fx.column(project, true), "open", AS_OF.minusMillis(1), owner);

    Snapshot snapshot = queries.snapshot(project, AS_OF);

    assertThat(snapshot.statuses()).isEmpty();
    assertThat(snapshot.assignees()).isEmpty();
  }
}
