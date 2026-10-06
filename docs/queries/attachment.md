# Task attachments — raw PostgreSQL queries

Frontend request/response contract: [Task attachments](../api-contracts/task-attachments.md).

Queries executed by `modules/attachment` for `POST` / `GET /tasks/{taskId}/attachments`,
`GET /tasks/{taskId}/attachments/{attachmentId}/download` and
`DELETE /tasks/{taskId}/attachments/{attachmentId}` (JSP-40), plus the trigger that queues
deleted files for removal from object storage.

`AttachmentService` gates every route with
`ProjectAccessService.ensureTaskRole(taskId, userId, role)` — `viewer` to list/download,
`member` to upload/delete — which runs [project.md](project.md) query 9 (project id of the
task) then query 1 (caller's membership) before anything below. Non-members get a masked
task-flavored 404, never a 403. `DELETE` by someone other than the uploader runs query 1
once more to read the caller's role (admin/owner may delete anyone's file).

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits, so the statements paste into `psql`.
- Parameters are `:name`. Example values: task id `'33333333-3333-4333-8333-333333333333'`,
  attachment id `'55555555-5555-4555-8555-555555555555'`, user id
  `'11111111-1111-4111-8111-111111111111'`.
- The `users` join selects every mapped column, `password_hash` included — it is loaded but
  never serialized (`User.toJson()` omits it), the same as `Comment.author`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Insert attachment | `AttachmentRepository.save` | `POST` |
| 2 | Attachment by id, with uploader | `AttachmentRepository.findByIdWithUploader` | `POST` (re-fetch for the response) |
| 3 | Page of a task's attachments, with uploader | `AttachmentRepository.findByTaskIdWithUploader` | `GET` list |
| 4 | Count for that page | `countQuery` of query 3 | `GET` list |
| 5 | Attachment by id on this task | `AttachmentRepository.findByIdAndTaskId` | `GET` download, `DELETE` |
| 6 | Delete attachment | `AttachmentRepository.deleteById` | `DELETE` |
| 7 | Queue the file for removal (trigger) | `fn_queue_attachment_file_deletion` | every deleted `task_attachments` row, cascades included |
| 8 | Next batch of queued files | `JpaStorageDeletionQueries.findBatch` | `StorageDeletionJob`, every 60 s |
| 9 | Remove processed keys | `JpaStorageDeletionQueries.remove` | `StorageDeletionJob` |
| 10 | Count a failed attempt | `JpaStorageDeletionQueries.recordFailures` | `StorageDeletionJob` |

## Queries

### 1. Insert attachment (JPA `save`)

Runs after the file is already in storage. If it fails the service deletes the stored file;
a foreign-key violation (the task was deleted mid-upload) becomes the masked task 404.
`created_at` comes from the database (`@CreationTimestamp(source = DB)`).

```sql
INSERT INTO task_attachments (id, task_id, uploaded_by, file_name, content_type, size_bytes, storage_key, created_at)
VALUES (:id, :taskId, :uploadedBy, :fileName, :contentType, :sizeBytes, :storageKey, now());

-- :fileName = 'login-bug.png', :contentType = 'image/png', :sizeBytes = 482133,
-- :storageKey = 'tasks/33333333-3333-4333-8333-333333333333/0b6f…'
```

### 2. Attachment by id, with uploader (JPQL + `@EntityGraph`)

```sql
SELECT a.id, a.task_id, a.uploaded_by, a.file_name, a.content_type, a.size_bytes, a.storage_key, a.created_at,
       u.id, u.email, u.full_name, u.password_hash, u.role, u.avatar_url, u.is_active, u.created_at, u.updated_at
FROM   task_attachments a
LEFT JOIN users u ON u.id = a.uploaded_by
WHERE  a.id = :id;
```

`LEFT JOIN`: `uploaded_by` is NULL once the uploader's account is deleted.

### 3. Page of a task's attachments, with uploader (JPQL + `@EntityGraph`)

Newest first; `id` breaks ties so page boundaries are stable. Served by
`idx_task_attachments_task_id_created_at`.

```sql
SELECT a.id, a.task_id, a.uploaded_by, a.file_name, a.content_type, a.size_bytes, a.storage_key, a.created_at,
       u.id, u.email, u.full_name, u.password_hash, u.role, u.avatar_url, u.is_active, u.created_at, u.updated_at
FROM   task_attachments a
LEFT JOIN users u ON u.id = a.uploaded_by
WHERE  a.task_id = :taskId
ORDER  BY a.created_at DESC, a.id DESC
LIMIT  :limit OFFSET :offset;

-- page=1&limit=20 → :limit = 20, :offset = 0
```

### 4. Count for that page

```sql
SELECT count(a.id) FROM task_attachments a WHERE a.task_id = :taskId;
```

### 5. Attachment by id on this task (derived finder)

Scoped by `task_id` as well as `id`: an attachment id belonging to another task is "not
found", so a member of project A can never reach a file of project B through A's task.

```sql
SELECT a.id, a.task_id, a.uploaded_by, a.file_name, a.content_type, a.size_bytes, a.storage_key, a.created_at
FROM   task_attachments a
WHERE  a.id = :id AND a.task_id = :taskId;
```

### 6. Delete attachment (Spring Data `deleteById`)

`deleteById` loads the row by primary key, then removes it. Nothing in the request calls
storage: the trigger (query 7) queues the file.

```sql
SELECT a.id, a.task_id, a.uploaded_by, a.file_name, a.content_type, a.size_bytes, a.storage_key, a.created_at
FROM   task_attachments a WHERE a.id = :id;

DELETE FROM task_attachments WHERE id = :id;
```

### 7. Queue the file for removal (trigger, verbatim from V6)

Runs once per deleted row, in the deleting transaction — including rows removed by
`ON DELETE CASCADE` when a task (or a parent task with its subtasks) is deleted. If that
transaction rolls back, so does the queue entry.

```sql
INSERT INTO storage_deletions (storage_key) VALUES (OLD.storage_key)
ON CONFLICT DO NOTHING;
```

### 8. Next batch of queued files (native, verbatim)

Fewest attempts first, then oldest, so keys that keep failing can never block the queue. No
row lock: the job holds no transaction while it calls storage, and a file deleted twice by two
app instances is harmless. The queue is normally empty or tiny, so there is no index beyond
the primary key.

```sql
SELECT storage_key FROM storage_deletions ORDER BY attempts, queued_at LIMIT :limit;

-- :limit = 100
```

### 9. Remove processed keys (native, verbatim)

```sql
DELETE FROM storage_deletions WHERE storage_key IN (:keys);
```

### 10. Count a failed attempt (native, verbatim)

A key whose `attempts` keeps growing points at a storage problem; check the job's
`Failed to delete stored file` warnings.

```sql
UPDATE storage_deletions SET attempts = attempts + 1 WHERE storage_key IN (:keys);
```

## Verify the trigger (local database only)

Paste into `psql` against a throwaway database with V1–V6 applied. Everything runs inside
one transaction and is rolled back.

```sql
BEGIN;
INSERT INTO users (id, email, full_name, password_hash)
VALUES ('11111111-1111-4111-8111-111111111111', 'jsp40@bench.test', 'Bench User', 'x');
INSERT INTO projects (id, name, tag) VALUES ('JSP40BNC', 'JSP-40 bench', 'JB');
INSERT INTO kanban_columns (name, project_id) VALUES ('JSP-40 bench column', 'JSP40BNC');
INSERT INTO tasks (id, title, column_id)
VALUES ('22222222-2222-4222-8222-222222222222', 'Parent',
        (SELECT id FROM kanban_columns WHERE name = 'JSP-40 bench column'));
INSERT INTO tasks (id, title, column_id, parent_id)
VALUES ('33333333-3333-4333-8333-333333333333', 'Subtask',
        (SELECT id FROM kanban_columns WHERE name = 'JSP-40 bench column'),
        '22222222-2222-4222-8222-222222222222');
INSERT INTO task_attachments (task_id, uploaded_by, file_name, content_type, size_bytes, storage_key) VALUES
  ('22222222-2222-4222-8222-222222222222', '11111111-1111-4111-8111-111111111111',
   'parent.png', 'image/png', 10, 'tasks/22222222-2222-4222-8222-222222222222/a'),
  ('33333333-3333-4333-8333-333333333333', '11111111-1111-4111-8111-111111111111',
   'child.pdf', 'application/pdf', 20, 'tasks/33333333-3333-4333-8333-333333333333/b');

-- Deleting the uploader keeps the rows: expect 2 rows, uploaded_by NULL
DELETE FROM users WHERE id = '11111111-1111-4111-8111-111111111111';
SELECT file_name, uploaded_by FROM task_attachments ORDER BY file_name;

-- Deleting the parent cascades to the subtask: expect both keys queued, attempts 0
DELETE FROM tasks WHERE id = '22222222-2222-4222-8222-222222222222';
SELECT storage_key, attempts FROM storage_deletions ORDER BY storage_key;

-- Expect 0
SELECT count(*) FROM task_attachments;
ROLLBACK;
```
