# Kanban columns — raw PostgreSQL queries

Queries executed by `modules/kanbancolumn` (`KanbanColumnController` / `KanbanColumnService` /
`KanbanColumnRepository`). Since RBAC Task 10 every route is `@JwtAuth` and gates on project
membership through `ProjectAccessService`; the gate queries themselves live in
[project.md](project.md) and are cross-referenced here as `project#N`.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (column lists simplified so it pastes into `psql`).
- Parameters are `:name`. Example values: column id `5` (serial int), project id `'UrzWUH3e'`
  (8 alphanumeric chars), user id `'11111111-1111-4111-8111-111111111111'` (uuid).
- Table: `kanban_columns (id SERIAL PK, name VARCHAR(100) UNIQUE, position INT, color VARCHAR(20),
  is_archived BOOLEAN, project_id VARCHAR(8) → projects, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)`.
- **Access gates** (from project.md): `project#1` = membership PK lookup (`getMembership` /
  `ensureRole`), `project#3` = project ids of a user (`getProjectIdsForUser`), `project#10` =
  project id of a column (`ensureColumnRole`, native). A non-member gets a masked 404 (column- or
  project-flavored); a member below the required role gets 403 — enforced before any column read/write.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Active columns for a set of projects | `KanbanColumnRepository.findByProjectIdInAndIsArchivedFalseOrderByPositionAsc` | `GET /columns` |
| 2 | Column by id | `KanbanColumnRepository.findById` | `GET /columns/{id}`, `PATCH /columns/{id}`, `DELETE /columns/{id}` |
| 3 | Insert column | `KanbanColumnRepository.saveAndFlush` (new) | `POST /columns` |
| 4 | Update column | `KanbanColumnRepository.saveAndFlush` (existing) | `PATCH /columns/{id}` |
| 5 | Delete column | `KanbanColumnRepository.deleteById` | `DELETE /columns/{id}` |
| 6 | Target-project admin gate (re-parenting) | `ProjectAccessService.ensureRole` (= `project#1`) | `PATCH /columns/{id}` (only when moving to a *different* `project_id`) |

## Queries

### 1. Active columns for a set of projects

`KanbanColumnRepository.findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(projectIds)` — derived
query. `KanbanColumnService.findAll` first resolves the caller's project ids (`project#3`); if the
caller belongs to no project it returns an empty list **without** hitting this table.

```sql
SELECT id, name, position, color, is_archived, project_id, created_at, updated_at
FROM kanban_columns
WHERE project_id IN (:projectIds)
  AND is_archived = false
ORDER BY position ASC;

-- :projectIds = ('UrzWUH3e', 'AbCdEf␣2')
```

### 2. Column by id

`KanbanColumnRepository.findById(id)` — PK lookup, via the private `getColumnOrThrow` after the
route's gate has passed. Missing row → `404 Column with id "5" not found`.

```sql
SELECT id, name, position, color, is_archived, project_id, created_at, updated_at
FROM kanban_columns
WHERE id = :id;
```

### 3. Insert column

`KanbanColumnService.create` → `saveAndFlush(new column)`, after `project#1` (`ensureRole` `admin`
on `dto.project_id`). The old `projectRepository.existsById` pre-check was **removed** — the gate's
masked 404 already covers a nonexistent/forbidden project. `id` is DB-generated (serial).

```sql
INSERT INTO kanban_columns (name, position, color, is_archived, project_id, created_at, updated_at)
VALUES (:name, :position, :color, false, :projectId, now(), now());
```

### 4. Update column

`KanbanColumnService.update` → `saveAndFlush(column)` on the row loaded by **#2**, after `project#10`
(`ensureColumnRole` `admin`, scoped to the column's *current* project). Unique-name violation → 409.

```sql
UPDATE kanban_columns
SET name = :name, position = :position, color = :color, project_id = :projectId, updated_at = now()
WHERE id = :id;
```

### 5. Delete column

`KanbanColumnService.remove` → `deleteById(id)` after `project#10` (`ensureColumnRole` `admin`) and a
`getColumnOrThrow` existence check (**#2**). A FK violation (column still has tasks) → 409.

```sql
DELETE FROM kanban_columns WHERE id = :id;
```

### 6. Target-project admin gate (re-parenting only)

When `update` receives a `project_id` that **differs** from the column's current project, it moves the
column to that project — which requires the caller to be an **admin of the target project too**, not
just the current one. So it runs `project#1` (`ProjectAccessService.ensureRole(targetProjectId, actorId,
ADMIN)` → the `project_members` PK lookup) on the target. `ensureRole` masks a nonexistent or non-member
target behind the same `404 Project with id "..." not found` — so, unlike the old `existsById` check, it
is not an existence oracle. Skipped entirely when `project_id` is absent or equal to the current project.

```sql
-- = project.md #1, against the TARGET project
SELECT project_id, user_id, role, joined_at
FROM project_members
WHERE project_id = :targetProjectId
  AND user_id    = :actorId;
```

## Endpoint query sequences

`project#N` = query N of [project.md](project.md) (the membership gate).

| Endpoint | Queries, in order |
|---|---|
| `GET /columns` 🔒 | `project#3` → **#1** (skipped when the caller has no projects) |
| `GET /columns/{id}` 🔒 | `project#10` (gate, `viewer`) → **#2** |
| `POST /columns` 🔒 | `project#1` (gate, `admin` on `dto.project_id`) → **#3** |
| `PATCH /columns/{id}` 🔒 | `project#10` (gate, `admin` on current) → **#2** → **#6** (`admin` on target, only when moving to a different project) → **#4** |
| `DELETE /columns/{id}` 🔒 | `project#10` (gate, `admin`) → **#2** → **#5** |

🔒 = `@JwtAuth`. Column routes gate in the **service** (via `ProjectAccessService`), not through the
`@RequireProjectRole` interceptor, because the path carries a column id, not a project id.
