package com.kanban.modules.dashboard;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plain-SQL fixtures for the dashboard ITs. Names that are UNIQUE across the whole schema (user email,
 * project name/tag, column name) carry a random suffix, so tests never collide and never need TRUNCATE.
 */
final class DashboardFixtures {
  private static final AtomicInteger SEQ = new AtomicInteger();
  private final JdbcTemplate jdbc;

  DashboardFixtures(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String unique() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT)
        + SEQ.incrementAndGet();
  }

  String user() {
    return jdbc.queryForObject(
        "INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, 'x') RETURNING CAST(id AS text)",
        String.class, "it-" + unique().toLowerCase(Locale.ROOT) + "@example.com", "IT User");
  }

  /** A project with {@code ownerId} as its OWNER member. */
  String project(String ownerId) {
    String id = unique().substring(0, 8);
    jdbc.update("INSERT INTO projects (id, name, tag, created_by) VALUES (?, ?, ?, CAST(? AS uuid))",
        id, "it-" + id, "T" + id.substring(0, 7), ownerId);
    member(id, ownerId, "owner");
    return id;
  }

  void member(String projectId, String userId, String role) {
    jdbc.update("INSERT INTO project_members (project_id, user_id, role) VALUES (?, CAST(? AS uuid), CAST(? AS project_role))",
        projectId, userId, role);
  }

  int column(String projectId, boolean archived) {
    return jdbc.queryForObject(
        "INSERT INTO kanban_columns (name, project_id, is_archived) VALUES (?, ?, ?) RETURNING id",
        Integer.class, "it-col-" + unique(), projectId, archived);
  }

  String task(int columnId, String status, Instant dueDate, String... assigneeIds) {
    return subtask(null, columnId, status, dueDate, assigneeIds);
  }

  String subtask(String parentId, int columnId, String status, Instant dueDate, String... assigneeIds) {
    String id = jdbc.queryForObject(
        "INSERT INTO tasks (title, column_id, status, due_date, parent_id)"
            + " VALUES ('it task', ?, CAST(? AS tasks_status_enum), ?, CAST(? AS uuid)) RETURNING CAST(id AS text)",
        String.class, columnId, status, dueDate == null ? null : OffsetDateTime.ofInstant(dueDate, ZoneOffset.UTC),
        parentId);
    for (String assigneeId : assigneeIds) {
      jdbc.update("INSERT INTO task_assignees (task_id, user_id) VALUES (CAST(? AS uuid), CAST(? AS uuid))",
          id, assigneeId);
    }
    return id;
  }

  void setStatus(String taskId, String status) {
    jdbc.update("UPDATE tasks SET status = CAST(? AS tasks_status_enum) WHERE id = CAST(? AS uuid)", status, taskId);
  }
}
