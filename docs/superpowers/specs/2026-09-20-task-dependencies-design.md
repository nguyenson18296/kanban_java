# Task dependencies — "blocks" / "blocked by" (JSP-33)

Design for a directed dependency link between tasks, with server-side cycle prevention.
A Java-only feature with no NestJS counterpart.

- **Ticket:** JSP-33
- **Branch:** `feat/JSP-33-task-dependencies` off `develop`, PR against `develop`
- **Status:** implemented; this document was written before the code and updated to match it

## 1. Goal

Let a task declare that it is blocked by one or more other tasks, and read both
directions of that relationship. The server rejects anything that would create a cycle,
walking the whole graph rather than only direct links.

### Out of scope (separate tickets)

1. Blocking a task from moving to `done` while it has unfinished blockers.
2. Surfacing dependencies in the task or board response payloads.
3. A Socket.IO broadcast for dependency changes.
4. Cross-project dependencies.

## 2. Edge model

One row is one directed edge:

```
(blocking_task_id, blocked_task_id)   ==   "blocking_task_id blocks blocked_task_id"
```

`POST /tasks/{id}/dependencies` with `blocked_by_ids: [B]` inserts `(B, id)`.

**The cycle rule, precisely.** Adding `(B, T)` closes a loop if and only if `B` is
already reachable from `T` by walking *blocks* edges forward. Seeding that walk at `T`
itself also catches `B == T`, so a single query enforces both the no-cycle and the
no-self-reference rule.

## 3. Module shape

New `modules/dependency`, mirroring `modules/subscription` — the same shape of thing: a
task sub-resource with its own table, a composite key, and a controller mounted on
`/tasks`.

```
src/main/java/com/kanban/modules/dependency/
  TaskDependency.java                    @Entity @IdClass, table task_dependencies
  TaskDependencyId.java                  Serializable composite id
  DependencyRepository.java              JpaRepository — JPQL projections, derived deletes
  DependencyQueries.java                 interface — recursive CTE, project filter, advisory lock
  JpaDependencyQueries.java              @Repository, EntityManager native SQL
  DependencyService.java                 gate, cycle check, writes, activity
  DependencyController.java              three routes
  dto/ManageDependenciesDto.java         ValidatedDto — blocked_by_ids
  dto/TaskDependenciesResponseDto.java   record — blocked_by / blocks + nested TaskSummaryDto
```

The split between `DependencyRepository` and `DependencyQueries` follows the CLAUDE.md
data-layer rule: Spring Data for what the repository API expresses, a `*Queries` interface
plus a `Jpa*Queries` implementation only for what it cannot — here the recursive CTE and
the advisory lock. `TaskService` (712 lines) is not touched.

## 4. Schema — `V5__create_task_dependencies.sql`

```sql
CREATE TABLE IF NOT EXISTS task_dependencies (
  blocking_task_id UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  blocked_task_id  UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  created_by       UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (blocking_task_id, blocked_task_id),
  CONSTRAINT chk_task_dependencies_no_self CHECK (blocking_task_id <> blocked_task_id)
);
CREATE INDEX IF NOT EXISTS idx_task_dependencies_blocked_task_id ON task_dependencies (blocked_task_id);

ALTER TYPE task_activity_action ADD VALUE IF NOT EXISTS 'task_dependency_added';
ALTER TYPE task_activity_action ADD VALUE IF NOT EXISTS 'task_dependency_removed';
```

Design notes:

- The composite primary key enforces **no duplicates** and indexes the forward walk
  (`blocking_task_id` leading). `idx_task_dependencies_blocked_task_id` covers the reverse
  lookup, matching the `idx_task_labels_label_id` convention on the other join tables.
- `ON DELETE CASCADE` on both FKs *is* the "deleting a task removes its dependency rows"
  acceptance criterion. No application code participates.
- `chk_task_dependencies_no_self` is a backstop; the service rejects self-reference first,
  with a proper HTTP body.
- The two `ALTER TYPE` statements follow the V3 precedent. They are safe inside Flyway's
  per-migration transaction because nothing in this file *uses* the new values — Postgres
  only forbids using an enum value in the same transaction that added it.

## 5. Wire contract

All three routes are mounted on the existing `/tasks` resource. `WebMvcConfig` supplies the
`/api` prefix, so mappings are written without it.

```
POST   /api/tasks/{id}/dependencies   201 → { blocked_by, blocks }
DELETE /api/tasks/{id}/dependencies   204 → no body
GET    /api/tasks/{id}/dependencies   200 → { blocked_by, blocks }
```

### Request body (POST and DELETE)

```json
{ "blocked_by_ids": ["9f1c…", "3ab2…"] }
```

`ManageDependenciesDto extends ValidatedDto`, bound with `@ValidatedBody`:

```java
@IsArray
@ArrayNotEmpty
@ArrayMaxSize(50)
@IsUUID(version = "4", each = true)
public List<String> blocked_by_ids;
```

DELETE-with-a-body matches the existing `DELETE /tasks/{id}/labels`. POST returns the
refreshed view, the way `addLabels` returns the updated task; DELETE is 204 with no body,
per the CLAUDE.md rule for new DELETE routes.

### Response body (POST and GET)

```json
{
  "blocked_by": [
    { "id": "9f1c…", "ticket_id": "KAN-12", "title": "Design schema",
      "status": "in_progress", "column_id": 3 }
  ],
  "blocks": [
    { "id": "7d4e…", "ticket_id": "KAN-19", "title": "Ship the API",
      "status": "open", "column_id": 1 }
  ]
}
```
ok
`TaskDependenciesResponseDto` is a record holding two `List<TaskSummaryDto>`, each entry
built by a JPQL constructor projection selecting only those five columns — not a full
`Task` entity load, and deliberately not `Task.toJson()`, which would couple this feature
to the relation-loading contract. Both lists are ordered by `ticket_number` ascending.

Field names are snake_case on the wire, per the repo-wide rule.

## 6. Authorization and error mapping

| Case | Response |
|---|---|
| Missing or invalid bearer token | 401 (`JwtAuthInterceptor`) |
| Malformed `{id}` in the path | 400 (`Param.Pipe.UUID`) |
| Body fails validation | 400 (`@ValidatedBody`) |
| Caller is not a member, or `{id}` is unknown | **404** `Task with id "X" not found` |
| Caller is a `viewer` on POST/DELETE | 403 `This action requires at least member role` |
| Any `blocked_by_ids` entry unknown, deleted, or in another project | **404** same body |
| Would create a cycle, or is a self-reference | 409 |

Gates: `GET` requires `VIEWER`, `POST`/`DELETE` require `MEMBER`, all through
`ProjectAccessService.ensureTaskRole(taskId, userId, role)`, which also returns the
project id the rest of the method needs.

**Why the masked 404 falls out for free.** `ensureTaskRole` resolves the task's project,
then looks up membership, and throws `taskNotFound` when membership is absent — *before*
comparing roles. A non-member therefore cannot reach the 403 branch on any of the three
routes, which is the acceptance criterion.

**Bad dependency targets** are collapsed into that same 404 by one query, so unknown,
deleted and cross-project ids are indistinguishable and the response never confirms that a
task exists in a project the caller cannot see:

```sql
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   tasks t
JOIN   kanban_columns c ON c.id = t.column_id
WHERE  t.id IN (:ids) AND c.project_id = :projectId;
```

Any requested id not in the result is rejected with the task-flavored 404. Only `id` is
needed for that check; the rest are carried so the cycle-error message (§6) and the
activity payload (§9) can name tasks without a second round trip, and the row maps
straight onto `TaskSummaryDto`.

**Cycle rejections are 409** (`ConflictException`) for both the transitive and the
self-reference case, since one query produces both. Messages name the offending task's
ticket id, which is safe: the caller is already a member of that project. Note that
`ticket_id` here is the task's own board ticket (`KAN-12`, set by the `fn_set_ticket_id`
trigger), unrelated to the Linear issue key.

- self-reference (the returned offender is `taskId` itself) — `A task cannot block itself`
- transitive — `Adding "KAN-12" would create a dependency cycle`

Ordering note: the argument resolvers run before the controller body, so a malformed body
yields 400 ahead of the membership gate. That is true of every other route in the repo and
leaks nothing about the task.

## 7. Read path

```sql
-- blocked_by: tasks that block :taskId
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   task_dependencies d
JOIN   tasks t ON t.id = d.blocking_task_id
WHERE  d.blocked_task_id = :taskId
ORDER  BY t.ticket_number;

-- blocks: tasks that :taskId blocks
SELECT t.id, t.ticket_id, t.title, t.status, t.column_id
FROM   task_dependencies d
JOIN   tasks t ON t.id = d.blocked_task_id
WHERE  d.blocking_task_id = :taskId
ORDER  BY t.ticket_number;
```

Expressed as two JPQL constructor-projection methods on `DependencyRepository`; the SQL
above is what they are equivalent to, and is what `docs/queries/dependency.md` will carry.

## 8. Write path

```java
@Transactional
public TaskDependenciesResponseDto add(String taskId, List<String> blockerIds, String actorId) {
  String projectId = access.ensureTaskRole(taskId, actorId, ProjectRole.MEMBER);
  List<String> ids = dedupe(blockerIds);

  // Serialize dependency writes within one project: two concurrent adds must not each
  // pass the cycle check and jointly close a loop.
  queries.lockProjectDependencies(projectId);

  ensureAllInProject(ids, projectId);                                // masked 404
  List<String> offenders = queries.findReachableFrom(taskId, ids);   // the recursive CTE
  if (!offenders.isEmpty()) {
    throw cycleConflict(taskId, offenders);
  }

  // Which edges are genuinely new, so activity reports only those — the same
  // existing-set diff addLabels does before saving.
  Set<String> already = repository.findBlockingTaskIdsIn(taskId, ids);
  List<String> created = ids.stream().filter(id -> !already.contains(id)).toList();

  repository.insertIgnoreAll(created, taskId, actorId);              // ON CONFLICT DO NOTHING
  if (!created.isEmpty()) {
    emitActivity(actorId, taskId, TASK_DEPENDENCY_ADDED, created);
  }
  return view(taskId);
}
```

`insertIgnoreAll` still carries `ON CONFLICT DO NOTHING` even though `created` is already
filtered: the diff is for the activity payload, the conflict clause is what keeps a
concurrent duplicate from turning into a 500.

### The cycle check

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

Every candidate is tested in one statement, and the offending ids come back for the error
message. `UNION` rather than `UNION ALL` deduplicates, so the walk terminates even if bad
data ever reached the table.

### The race guard

```sql
SELECT pg_advisory_xact_lock(hashtext(:projectId));
```

Taken before the checks so that the check and the insert are both inside it, and released
automatically at commit. Contention is bounded to concurrent dependency writes within a
single project. Without it, two simultaneous writers can each pass the cycle check and
together create a cycle that nothing afterwards detects or repairs.

### Atomicity

One offending id rejects the entire request, and `@Transactional` guarantees the "writes
nothing" half of the acceptance criterion. Ids that already exist as edges are a silent
no-op via `ON CONFLICT DO NOTHING`, matching how `addLabels` ignores labels already
attached.

### Removal

`remove` takes the same gate (`MEMBER`), then reads the matching edges before deleting them
and reports exactly those. It deliberately does **not** project-check its targets: a task
can be moved into another project's column (`PATCH /tasks/:id/move`), and rejecting a
now-foreign blocker would leave an edge that `GET` still lists but nothing can remove.
Membership on the task is what authorizes the delete, and only edges pointing at that task
can be removed, so an unchecked blocker id leaks nothing. It needs neither the
advisory lock nor the cycle check — removing edges cannot close a loop. A delete matching
no edge issues no `DELETE` at all and records nothing.

The pre-read is what makes "activity only for rows actually deleted" achievable: the
delete count alone says how many rows went, never which.

## 9. Activity

Two new `TaskActivityAction` values, `TASK_DEPENDENCY_ADDED` and
`TASK_DEPENDENCY_REMOVED`, emitted on the blocked task through the existing `EventBus` as
`TaskActivityEvent`. The payload mirrors the label payload shape:

```java
Json.map("dependencies", List.of(
    Json.map("task_id", …, "ticket_id", …, "title", …)));
```

Emitted only for edges actually created or removed — the `created` / deleted diff computed
in §8, so an all-duplicate POST and a no-op DELETE record nothing, matching
`addLabels` / `removeLabels`. The `ticket_id` and `title` for the payload come from the
same in-project resolution query that already ran for the 404 check, so no extra round
trip is needed.

## 10. Testing

No database is available to the default test suite, so the cycle rule is verified in Java
against a fake `DependencyQueries` that walks a canned edge map. That keeps the ticket's
headline criterion executable in `mvn test`; the SQL itself stays unverified by tests, as
every other native query in this repo already is.

**`DependencyServiceTest`** (plain JUnit 5 + `mock(...)` + `RecordingEventBus`):

- chain `A→B→C` exists; `C→A` is rejected with 409 and no repository write occurs
- direct `A→B` then `B→A` is rejected
- self-reference is rejected
- an id already linked is a silent no-op, and emits no activity
- an id in another project → 404 on add, with the task-flavored body, not 400 or 403
- a stale edge whose blocker has since moved to another project is still removable
- an unknown id → the same 404
- a viewer on add/remove → 403; a non-member → 404
- a successful add emits `task_dependency_added` once, listing only new edges
- removal deletes matching rows and emits `task_dependency_removed` only for rows deleted

There is no separate `DependencyControllerTest`. `SubscriptionController` gates in the
controller, so its test has gating to assert; here the gate lives in the service — which
needs the project id the gate returns — leaving the controller as pure delegation, covered
end to end by `WebLayerTest`.

**`WebLayerTest`** — add `DependencyController` to `@WebMvcTest(controllers = …)` with a
`@MockitoBean DependencyService`, pinning 201/204/200, the validation error body for a
missing or empty `blocked_by_ids`, and the 400 body for a malformed path UUID.

## 11. Documentation (same PR)

- **`docs/queries/dependency.md`** — new, following the `docs/queries/subscription.md`
  format: an index table, then every query this module runs as raw PostgreSQL, including
  the ones Spring Data derives.
- **`MIGRATION.md`** — a row in §1 (runtime & framework mapping) and §2 (module-by-module)
  marked Java-only, a row in §4 (test mapping), and a numbered entry in §6 recording this
  as a deliberate Spring-only addition with no Nest counterpart.
- **`CLAUDE.md`** — add `modules/dependency` to the package layout, and move the "latest
  migration" pointer from `V4__add_task_search_vector.sql` to
  `V5__create_task_dependencies.sql`.
- **`docs/queries/task.md`** — one line noting that deleting a task cascades to
  `task_dependencies`.

The ticket also cites `.claude/rules/docs.md`, which does not exist in the repo or in
`~/.claude/rules/`. The list above is taken from the ticket body and CLAUDE.md instead.

## 12. Acceptance criteria → where each is satisfied

| Criterion | Satisfied by |
|---|---|
| Chain `A→B→C`; `C→A` rejected, writes nothing | §8 recursive CTE + `@Transactional`; test in §10 |
| Non-member gets 404 on all three routes, never 403 | §6 — `ensureTaskRole` throws before the role comparison |
| Viewer can read, cannot add or remove | §6 — `VIEWER` on GET, `MEMBER` on POST/DELETE |
| Deleting a task removes its dependency rows | §4 — `ON DELETE CASCADE` on both FKs |
| Swagger renders all three routes | §5 — `@Tag`, `@Operation`, `@ApiResponse`, `@Parameter` per the API conventions |
| `mvn test` green, docs updated in the same PR | §10, §11 |
