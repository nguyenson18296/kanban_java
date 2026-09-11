# Task activities — raw PostgreSQL queries

Queries executed by `modules/activity` for `GET /tasks/{taskId}/activities` and the async
activity writes (`ActivityListener`). As of JAV-20 the read gates membership first via
`ProjectAccessService.ensureTaskRole(taskId, userId, viewer)` ([project.md](project.md)
queries 9 + 1), replacing the previous `tasks` existence check — `ActivityService` no
longer depends on `TaskRepository`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Activities of a task, filtered + paginated (+ actor) | `ActivityQueriesImpl.findByTaskFiltered` | `GET /tasks/{taskId}/activities` |
| 2 | Insert activity | `ActivityRepository.save` (via `ActivityService.create`) | `ActivityListener` (async, after task mutations) |

## Queries

### 1. Activities of a task, filtered + paginated

`?page=` (default 1), `?limit=` (default 20), optional `?action=` filter. Data query plus an
explicit count query; the actor loads via `left join fetch`.

```sql
SELECT a.*, u.*
FROM task_activities a
LEFT JOIN users u ON u.id = a.actor_id
WHERE a.task_id = :taskId
  -- AND a.action = :action   (only when ?action= is given)
ORDER BY a.created_at ASC
LIMIT :limit OFFSET :offset;

SELECT count(*) FROM task_activities WHERE task_id = :taskId; -- same optional action filter
```

### 2. Insert activity

```sql
INSERT INTO task_activities (id, task_id, actor_id, action, payload, created_at) VALUES (...);
```
