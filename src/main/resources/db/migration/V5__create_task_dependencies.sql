-- ---------------------------------------------------------------------------
-- task_dependencies (JSP-33)
--
-- One row is one directed edge: blocking_task_id blocks blocked_task_id.
-- POST /api/tasks/{id}/dependencies with blocked_by_ids:[B] inserts (B, id).
--
-- The composite primary key rejects duplicates and indexes the forward walk
-- used by the cycle check; idx_task_dependencies_blocked_task_id covers the
-- reverse direction. ON DELETE CASCADE on both FKs is what removes a deleted
-- task's dependency rows -- no application code participates.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_dependencies (
  blocking_task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  blocked_task_id  UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  created_by       UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocking_task_id, blocked_task_id),
  CONSTRAINT chk_task_dependencies_no_self CHECK (blocking_task_id <> blocked_task_id)
);
CREATE INDEX IF NOT EXISTS idx_task_dependencies_blocked_task_id ON task_dependencies (blocked_task_id);

-- Safe inside Flyway's per-migration transaction: Postgres only forbids USING a
-- new enum value in the transaction that added it, and nothing here uses them.
ALTER TYPE task_activity_action ADD VALUE IF NOT EXISTS 'task_dependency_added';
ALTER TYPE task_activity_action ADD VALUE IF NOT EXISTS 'task_dependency_removed';
