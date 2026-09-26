# Task dependencies — raw PostgreSQL queries

Frontend request/response contract: [Task dependencies](../api-contracts/task-dependencies.md).

Queries executed by `modules/dependency` for `POST`, `DELETE` and `GET`
`/tasks/{id}/dependencies` (JSP-33). One row of `task_dependencies` is one directed edge:
`blocking_task_id` blocks `blocked_task_id`. A `POST` with `blocked_by_ids: [B]` on task
`T` inserts `(B, T)`.

`DependencyService` gates every route with
`ProjectAccessService.ensureTaskRole(taskId, userId, role)` — `viewer` to read, `member` to
write — which runs [project.md](project.md) query 9 (project id of the task) then query 1
(caller's membership) before anything below. Non-members get a masked task-flavored 404,
never a 403.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits; JPQL implicit joins are written as the
  inner joins they resolve to, so the statements paste into `psql`.
- Parameters are `:name`. Example values: task id `'33333333-3333-4333-8333-333333333333'`,
  project id `'UrzWUH3e'`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Per-project advisory lock | `JpaDependencyQueries.lockProjectDependencies` | `POST` only |
| 2 | Resolve dependency targets in project | `JpaDependencyQueries.findTasksInProject` | `POST` (gate); `DELETE` (activity labels only) |
| 3 | Reachability walk (cycle check) | `JpaDependencyQueries.findReachableFrom` | `POST` only |
| 4 | Existing blockers among candidates | `DependencyRepository.findExistingBlockers` | `POST`, `DELETE` |
| 5 | Insert edge | `DependencyRepository.insertIgnore` | `POST` |
| 6 | Delete edges | `DependencyRepository.deleteEdges` | `DELETE` |
| 7 | Tasks that block this task | `DependencyRepository.findBlockedBy` | `GET`, and the `POST` response |
| 8 | Tasks this task blocks | `DependencyRepository.findBlocks` | `GET`, and the `POST` response |

## Queries

### 1. Per-project advisory lock (native, verbatim)

Held until the transaction commits. Serializes dependency writes within one project so two
concurrent adds cannot each pass the cycle check and jointly close a loop. Taken *before*
queries 2 and 3, so the check and the insert are both inside it.

```sql
SELECT pg_advisory_xact_lock(hashtext(:projectId));

-- :projectId = 'UrzWUH3e'
```

### 2. Resolve dependency targets in project (native, verbatim)

On `POST`, every requested blocker id must come back, or the request is rejected with the same
task-flavored 404 an unknown id gets. Unknown, deleted and cross-project ids are therefore
indistinguishable, so the response never confirms a task exists in a project the caller
cannot see. `ticket_id` and `title` are carried for the cycle-error message and the
activity payload, saving a second round trip.

```sql
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   tasks t
JOIN   kanban_columns c ON c.id = t.column_id
WHERE  t.id IN (:ids) AND c.project_id = :projectId;
```

### 3. Reachability walk — the cycle check (native, verbatim)

Of the requested blockers, those already reachable from the task by walking blocks-edges
forward. A non-empty result means the edge would close a cycle, and the whole request is
rejected with 409 without writing anything.

`UNION` rather than `UNION ALL`: the deduplication makes the walk terminate even if a cycle
ever reached the table. The seed row is `:taskId` itself, so a candidate equal to it comes
back too — that is the self-reference case, reported as `A task cannot block itself`.

```sql
WITH RECURSIVE downstream(id) AS (
  SELECT CAST(:taskId AS uuid)
  UNION
  SELECT d.blocked_task_id
  FROM   task_dependencies d
  JOIN   downstream s ON d.blocking_task_id = s.id
)
SELECT id FROM downstream WHERE id IN (:candidateIds);
```

The recursive term walks `blocking_task_id` → `blocked_task_id`, served by the
`task_dependencies` primary key (`blocking_task_id` leading).

`DELETE` runs the same query but never rejects on a miss — it only wants `ticket_id` and
`title` for the activity payload. Membership on `:taskId` is what authorizes the delete, and
only edges pointing at `:taskId` can be removed, so a blocker outside the project leaks
nothing. Rejecting there would instead make an edge unremovable once its blocker moved to
another project (`PATCH /tasks/:id/move` permits that) while query 7 still listed it.

### 4. Existing blockers among candidates

Which of the requested edges already exist. On `POST` the complement is what gets inserted
and reported as activity; on `DELETE` the result *is* what gets deleted, so activity can
name the rows that actually went rather than a count.

```sql
SELECT d.blocking_task_id
FROM   task_dependencies d
WHERE  d.blocked_task_id = :taskId AND d.blocking_task_id IN (:candidateIds);
```

### 5. Insert edge (native, verbatim)

One statement per new edge. `ON CONFLICT DO NOTHING` is not what filters duplicates —
query 4 already did — it keeps a concurrent duplicate from surfacing as a 500.

```sql
INSERT INTO task_dependencies (blocking_task_id, blocked_task_id, created_by)
VALUES (CAST(:blockingTaskId AS uuid), CAST(:blockedTaskId AS uuid), CAST(:createdBy AS uuid))
ON CONFLICT DO NOTHING;
```

### 6. Delete edges

```sql
DELETE FROM task_dependencies
WHERE  blocked_task_id = :taskId AND blocking_task_id IN (:blockingTaskIds);
```

### 7. Tasks that block this task (`blocked_by`)

Constructor projection into `TaskSummaryDto` — five columns, not a `Task` entity load.

```sql
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   task_dependencies d
JOIN   tasks t ON t.id = d.blocking_task_id
WHERE  d.blocked_task_id = :taskId
ORDER  BY t.ticket_number ASC;
```

### 8. Tasks this task blocks (`blocks`)

Served by `idx_task_dependencies_blocked_task_id` in the reverse direction.

```sql
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   task_dependencies d
JOIN   tasks t ON t.id = d.blocked_task_id
WHERE  d.blocking_task_id = :taskId
ORDER  BY t.ticket_number ASC;
```

## Not queried here

Deleting a task removes its dependency rows through `ON DELETE CASCADE` on both foreign
keys (`V5__create_task_dependencies.sql`) — no application query participates.
