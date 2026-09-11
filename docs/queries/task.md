# Tasks — raw PostgreSQL queries

Queries executed by `modules/task` for the `/tasks` routes (`TaskService`, `TaskRepository`,
`TaskController`). As of JAV-20 **every task route is membership-gated**: before anything
below, each request runs the access-gate sequence from [project.md](project.md) — query 9
(project id of the task) then query 1 (caller's membership) via
`ProjectAccessService.ensureTaskRole`. Exceptions: `POST /tasks` resolves the column and
gates with `ensureRole` on its project (query 1), and `GET /tasks` scopes by the caller's
project ids (query 3) instead of gating. Reads require `viewer`, mutations `member`;
non-members get a masked task-flavored 404 (anti-enumeration).

Mutation internals (save-then-refetch graphs, junction-table writes, the
`fn_move_task` / `fn_reorder_task` / `fn_reorder_subtask` stored procedures behind
`JpaTaskPositionFunctions`) are not documented yet — add them here when those paths change;
JAV-20 only prepended the gate to them.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (aliases and column lists simplified so
  it pastes into `psql`). `@EntityGraph` relation loads are noted, not expanded into JOINs.
- Parameters are `:name`. Example values: task id `'22222222-2222-4222-8222-222222222222'`,
  project id `'UrzWUH3e'`.

## Gate per route

| Route | Gate before the queries below |
|---|---|
| `GET /tasks` | caller's project ids (project.md query 3); `[]` short-circuits without touching `tasks` |
| `GET /tasks/{id}`, `GET /tasks/{id}/subtasks` | `ensureTaskRole(id, user, viewer)` |
| `GET /tasks/by-ticket/{ticketId}` | task loaded first (query 3 below), then `ensureTaskRole(task.id, user, viewer)` — an unknown ticket id still 404s with the ticket-id message |
| `POST /tasks` | column lookup (query 8) + `ensureRole(column.project_id, user, member)` |
| `PATCH /tasks/{id}/move` | `ensureTaskRole(id, user, member)` on the source project **and**, when the target column (query 8) is in a different project, `ensureRole(targetColumn.project_id, user, member)` — a cross-project move requires membership on both ends |
| every other mutation (`PATCH`/`DELETE`/subtask routes/assignees/labels) | `ensureTaskRole(taskId, user, member)` |

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Top-level tasks scoped to the caller's projects | `TaskRepository.findTopLevelWithRelationsByProjectIds` | `GET /tasks` (JAV-20; replaced the unscoped `findTopLevelWithRelations`) |
| 2 | Task by id + full relations | `TaskRepository.findByIdWithFullRelations` | `GET /tasks/{id}`; every mutation's save-then-refetch |
| 3 | Task by ticket id + full relations | `TaskRepository.findByTicketIdWithFullRelations` | `GET /tasks/by-ticket/{ticketId}` |
| 4 | Subtasks of a parent | `TaskRepository.findSubtasksWithRelations` | `GET /tasks/{id}/subtasks` |
| 5 | parent_id of a task | `TaskRepository.findParentIdRowById` | subtask-depth validation on create/update/create-subtask |
| 6 | column_id of a task | `TaskRepository.findColumnIdRowById` | `PATCH /tasks/{id}/move` (activity payload) |
| 7 | Task exists? | `TaskRepository.existsById` | reorder / reorder-subtask precheck |
| 8 | Column by id | `KanbanColumnRepository.findById` | `POST /tasks`, `PATCH /tasks/{id}/move` |

## Queries

### 1. Top-level tasks scoped to the caller's projects (JAV-20)

`TaskRepository.findTopLevelWithRelationsByProjectIds(projectIds)` — JPQL with a column
subquery (`Task` has a plain `column_id`, no relation to `kanban_columns`). Relations
assignees, labels, creator, subtasks, subtasks.parent load via `@EntityGraph`.
`TaskService.findAllForUser` returns `[]` without running this when the caller has no
memberships.

```sql
SELECT t.*
FROM tasks t
WHERE t.parent_id IS NULL
  AND t.column_id IN (SELECT c.id FROM kanban_columns c WHERE c.project_id IN (:projectIds));

-- :projectIds = ('UrzWUH3e', 'Ab3dEf9h')
```

### 2. Task by id + full relations

`TaskRepository.findByIdWithFullRelations(id)` — relations assignees, labels, creator,
subtasks, subtasks.parent, parent via `@EntityGraph`.

```sql
SELECT t.* FROM tasks t WHERE t.id = :id;
```

### 3. Task by ticket id + full relations

`TaskRepository.findByTicketIdWithFullRelations(ticketId)` — same relation set as #2.

```sql
SELECT t.* FROM tasks t WHERE t.ticket_id = :ticketId;

-- :ticketId = 'KAN-1'
```

### 4. Subtasks of a parent

`TaskRepository.findSubtasksWithRelations(parentId)` — relations assignees, labels,
creator, subtasks, parent.

```sql
SELECT t.* FROM tasks t WHERE t.parent_id = :parentId ORDER BY t.position ASC;
```

### 5. parent_id of a task

```sql
SELECT t.parent_id FROM tasks t WHERE t.id = :id;
```

### 6. column_id of a task

```sql
SELECT t.column_id FROM tasks t WHERE t.id = :id;
```

### 7. Task exists?

```sql
SELECT count(*) > 0 FROM tasks t WHERE t.id = :id;
```

### 8. Column by id

```sql
SELECT c.* FROM kanban_columns c WHERE c.id = :columnId;
```
