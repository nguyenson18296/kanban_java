-- ---------------------------------------------------------------------------
-- task_attachments (JSP-40)
--
-- One row per file attached to a task. The bytes live in object storage under
-- storage_key; this table holds what the API lists. ON DELETE CASCADE on
-- task_id removes a deleted task's rows (and its subtasks' rows, through
-- tasks.parent_id ON DELETE CASCADE).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS task_attachments (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id      UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  uploaded_by  UUID REFERENCES users(id) ON DELETE SET NULL,
  file_name    VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  size_bytes   BIGINT NOT NULL CHECK (size_bytes > 0),
  storage_key  VARCHAR(512) NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_attachments_task_id_created_at
  ON task_attachments (task_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- storage_deletions: files still waiting to be removed from object storage.
-- Filled only by the trigger below; drained by StorageDeletionJob.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS storage_deletions (
  storage_key VARCHAR(512) PRIMARY KEY,
  queued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts    INT NOT NULL DEFAULT 0
);

-- Row triggers also fire for rows removed by ON DELETE CASCADE, so every way an
-- attachment row can disappear queues its file -- no application code participates.
CREATE OR REPLACE FUNCTION fn_queue_attachment_file_deletion()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO storage_deletions (storage_key) VALUES (OLD.storage_key)
  ON CONFLICT DO NOTHING;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_task_attachments_queue_file_deletion ON task_attachments;
CREATE TRIGGER trg_task_attachments_queue_file_deletion
  AFTER DELETE ON task_attachments
  FOR EACH ROW
  EXECUTE FUNCTION fn_queue_attachment_file_deletion();
