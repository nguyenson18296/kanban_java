# Task Dependencies

Directed "blocked by" links between tasks — say that task B must finish before task A — with
server-side cycle prevention. Three routes under `/api/tasks/{id}/dependencies`, built in
[JSP-33](https://linear.app/java-son-182/issue/JSP-33/backend-task-dependencies-blocks-blocked-by)
(PR #20). A Spring-only addition with no NestJS counterpart. Deep details:
[docs/queries/dependency.md](../queries/dependency.md) ·
[frontend contract](../api-contracts/task-dependencies.md).

## 1. What It Does

The API had no way to express "finish X before starting Y" — something Jira and Linear both model.
Now:

- `POST` declares one or more tasks as blockers of a task; `DELETE` removes links; `GET` lists
  both directions (what blocks this task, and what it blocks).
- The server rejects anything that would create a cycle — including through a chain (A→B→C, then
  C blocking A) — and self-references.
- Adding or removing links records a task activity, like assignees and labels do.

Out of scope (separate tickets): blocking a task from moving to `done` while blockers are open,
showing dependencies inside task/board payloads, a Socket.IO broadcast, cross-project links.

## 2. How It Works

Code lives in `com.kanban.modules.dependency`. One `task_dependencies` row is one directed edge:
`blocking_task_id` blocks `blocked_task_id`.

`POST` (`DependencyService.add`):

1. `ensureTaskRole(taskId, userId, MEMBER)` — non-members get the masked 404 (§5).
2. `blocked_by_ids` are canonicalized: lowercased (Postgres returns uuids in lowercase, and the
   UUID validators accept any casing) and deduplicated.
3. A per-project **advisory lock** (a Postgres application-level lock, released at commit) is
   taken *before* any check, so two concurrent adds can't each pass the cycle check and jointly
   close a loop.
4. Every blocker id must resolve to a task in this project, or the whole request 404s.
5. The cycle check walks the whole graph with a recursive SQL query; any hit → 409, nothing
   written.
6. Only edges that don't already exist are inserted; the activity event lists exactly those.

`DELETE` (`DependencyService.remove`) deliberately skips step 4: membership on the route task is
the authorization, and only edges pointing at that task can go — so a blocker that has since moved
to another project stays removable instead of becoming a stuck edge. Removing edges can't create a
cycle, so no lock and no cycle check. `GET` is two summary queries (blockers, blocked).

Key decisions, and why:

- **Lock before check** — the cycle check and the insert must sit inside the same lock, or two
  concurrent adds could each see "no cycle" and together create one.
- **`UNION` (not `UNION ALL`) in the graph walk** — deduplication makes the walk terminate even if
  a cycle ever reached the table.
- **Diff-then-insert** — `findExistingBlockers` decides what is new; `ON CONFLICT DO NOTHING` on
  the insert only shields against a concurrent duplicate becoming a 500.

## 3. API

All three routes: `@JwtAuth`; body is `{ "blocked_by_ids": [...] }` for POST and DELETE
(`Content-Type: application/json` required, also on DELETE).

| Route | Role | Success | Errors |
|---|---|---|---|
| `POST /api/tasks/{id}/dependencies` | member | 201 + both directions | 400, 401, 404 (task or blocker), 409 (cycle/self) |
| `DELETE /api/tasks/{id}/dependencies` | member | 204 (unknown links ignored) | 400, 401, 404 (task only) |
| `GET /api/tasks/{id}/dependencies` | viewer | 200 | 401, 404 |

`blocked_by_ids`: 1–50 entries, each a version-4 UUID, any casing (canonicalized to lowercase),
duplicates collapsed. A member below the required role gets 403. Trimmed response shape:

```json
{ "blocked_by": [ { "id": "…", "ticket_id": "KAN-7", "title": "…", "status": "open", "column_id": 1 } ],
  "blocks": [] }
```

Full payloads, error bodies and integration rules:
[task-dependencies contract](../api-contracts/task-dependencies.md).

## 4. Database

`V5__create_task_dependencies.sql` added the edge table and two activity enum values:

```sql
CREATE TABLE task_dependencies (
  blocking_task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  blocked_task_id  UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  created_by       UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocking_task_id, blocked_task_id),
  CONSTRAINT chk_task_dependencies_no_self CHECK (blocking_task_id <> blocked_task_id)
);
CREATE INDEX idx_task_dependencies_blocked_task_id ON task_dependencies (blocked_task_id);
```

The composite primary key rejects duplicates *and* indexes the forward graph walk; the extra index
covers the reverse direction. Deleting a task removes its edges via `ON DELETE CASCADE` — no
application code participates. Every query, with rationale:
[docs/queries/dependency.md](../queries/dependency.md).

## 5. Security

- Reads gate at `viewer`, writes at `member`, via `ProjectAccessService.ensureTaskRole`.
- **Masked 404 everywhere**: a non-member, an unknown task, and (on POST) a blocker outside the
  project all get the identical task-flavored 404 — the API never confirms a task exists in a
  project the caller can't see. Below-role members get 403.
- DELETE ignoring unknown blockers leaks nothing: only edges pointing at the caller's own task can
  be removed.
- Input is bound as SQL parameters (no injection) and validated to 1–50 v4 UUIDs before the
  service runs; ids are canonicalized so casing can't bypass dedup or matching.
- The 409 cycle message names the blocker by `ticket_id` (or its id) — safe, because every blocker
  was already resolved as visible to the caller in step 4.

## 6. Testing

- `DependencyServiceTest` (22 tests) — the cycle rule runs against a real in-memory graph walk
  (`FakeDependencyQueries` mirrors the recursive SQL): transitive/direct/self cycles,
  all-or-nothing rejection, lock-before-check ordering, masked 404s, casing canonicalization,
  activity diffing. Run: `mvn test -Dtest=DependencyServiceTest`
- `JpaDependencyQueriesTest` — the row mapper fails fast on an unknown status value.
- `WebLayerTest` — the three routes' status codes, 401 body, validation bodies.
- Not covered: the native SQL (recursive walk, advisory lock) is unverified by tests, like every
  native query in this repo.

## 7. Gotchas & Limitations

- The advisory lock keys on `hashtext(projectId)` (a 32-bit hash): two projects can collide and
  briefly serialize each other's dependency writes. Harmless, just occasional extra waiting.
- `GET` can list a blocker that has since moved to another project; opening it may 404. This is by
  design — see the DELETE rationale in §2.
- Nothing stops a task from being marked `done` while its blockers are open — deliberately out of
  scope (§1).
- Cosmetic: a self-block request whose *path* id is uppercase returns the generic cycle 409
  message instead of "A task cannot block itself" (body ids are canonicalized; the path id is not).
- Changing behaviour? Update in the same PR: `docs/queries/dependency.md`,
  `docs/api-contracts/task-dependencies.md`, `MIGRATION.md` (§6.13), and this file.

## 8. Where to Look Next

- Code: `src/main/java/com/kanban/modules/dependency/` (controller, service, repository, native
  queries, DTOs)
- SQL with per-query rationale: [docs/queries/dependency.md](../queries/dependency.md)
- Frontend contract: [docs/api-contracts/task-dependencies.md](../api-contracts/task-dependencies.md)
- Migration: `src/main/resources/db/migration/V5__create_task_dependencies.sql`
- Ticket: [JSP-33](https://linear.app/java-son-182/issue/JSP-33/backend-task-dependencies-blocks-blocked-by) · PR #20 · `MIGRATION.md` §6.13
