# Comments — raw PostgreSQL queries

Queries executed by `modules/comment` (`CommentService`, `CommentRepository`) for
`/tasks/{taskId}/comments` and `/comments/{id}`. As of JAV-20 the two task-scoped routes
gate membership first via `ProjectAccessService.ensureTaskRole` (queries 9 + 1 in
[project.md](project.md)): `POST` requires `member`, the paginated `GET` requires `viewer`.
Comment update/delete stay **ownership**-gated in the service (`author_id = caller`), not
role-gated. The old `ensureTaskExists` existence probe was removed — the gate's masked 404
covers it.

**How to read this file** — see [task.md](task.md); example comment id
`'33333333-3333-4333-8333-333333333333'`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Comment by id + author | `CommentRepository.findByIdWithAuthor` | `POST` refetch; `PATCH`/`DELETE /comments/{id}` |
| 2 | Comments of a task, paginated + author | `CommentRepository.findByTaskIdWithAuthor` | `GET /tasks/{taskId}/comments` |
| 3 | Task by id | `TaskRepository.findById` | `POST /tasks/{taskId}/comments` (loads the task for notification payloads) |
| 4 | Insert comment | `CommentRepository.save` | `POST /tasks/{taskId}/comments` |
| 5 | Delete comment | `CommentRepository.deleteById` | `DELETE /comments/{id}` |
| 6 | Auto-subscribe + fan-out reads | `TaskSubscriptionRepository` | `POST` side effects — see [subscription.md](subscription.md) |

## Queries

### 1. Comment by id + author

```sql
SELECT c.*, u.*
FROM task_comments c
LEFT JOIN users u ON u.id = c.author_id
WHERE c.id = :id;
```

### 2. Comments of a task, paginated + author

`?page=` (default 1), `?limit=` (default 20), `?sort=asc|desc` (default `desc`), ordered by
`created_at`. Runs a data query plus an explicit count query.

```sql
SELECT c.*, u.*
FROM task_comments c
LEFT JOIN users u ON u.id = c.author_id
WHERE c.task_id = :taskId
ORDER BY c.created_at DESC
LIMIT :limit OFFSET :offset;

SELECT count(*) FROM task_comments WHERE task_id = :taskId;
```

### 3. Task by id

```sql
SELECT t.* FROM tasks t WHERE t.id = :taskId;
```

### 4. Insert comment

```sql
INSERT INTO task_comments (id, task_id, author_id, content, ...) VALUES (...);
```

### 5. Delete comment

```sql
DELETE FROM task_comments WHERE id = :id;
```
