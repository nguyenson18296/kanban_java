# Task dependencies contract — frontend integration

HTTP contract for "blocks" / "blocked by" links between tasks (JSP-33), matching the current
backend implementation. Use this alongside Swagger (`/api/docs`, tag **Task Dependencies**);
database queries are documented in [dependency.md](../queries/dependency.md). Dependency changes
emit no Socket.IO events; the [socket contract](socket-events.md) is unchanged.

| | |
|---|---|
| Endpoints | `GET` / `POST` / `DELETE` `/api/tasks/{id}/dependencies` |
| Base URL | Default `http://localhost:1996/api` |
| Auth | `Authorization: Bearer <access_token>` |
| Roles | Any project member can read; `member` or higher can add/remove |
| Scope | Both tasks must be in the same project |
| Request | JSON `{ blocked_by_ids: string[] }` (POST and DELETE) |
| Response | JSON `{ blocked_by, blocks }` (GET and POST); DELETE → `204`, no body |
| Casing | **snake_case** |

**Changelog**

- 2026-09-25 (JSP-33): uppercase UUIDs in `blocked_by_ids` are now accepted and canonicalized to
  lowercase (previously a known gap: `POST` rejected them with `404`). Responses and activity
  entries always carry lowercase ids.
- 2026-09-25 (JSP-33): restructured to the `writing-api-contracts` template: added a typed-payload
  block and an activity example, a frontend-handling column for errors, and a shorter React Query
  reference.
- 2026-09-25 (JSP-33): documented the three dependency routes, payloads, cycle rule, errors,
  activity entries, and frontend integration.

---

## 1. Request & authentication

Every route requires the `access_token` from login or token refresh. Missing, invalid or expired
tokens, and deleted or inactive users, receive `401`. The caller also needs a **project** role on
the task's project (`owner > admin > member > viewer`), not the user's job role:

| Method | Path | Minimum role | Success |
|---|---|---|---|
| `GET` | `/api/tasks/{id}/dependencies` | `viewer` | `200` `{ blocked_by, blocks }` |
| `POST` | `/api/tasks/{id}/dependencies` | `member` | `201` `{ blocked_by, blocks }` |
| `DELETE` | `/api/tasks/{id}/dependencies` | `member` | `204`, empty body |

`{id}` is the task UUID (any UUID version). A non-member gets the same `404` as a task that does
not exist, never a `403` (§5). POST and DELETE take the same JSON body:

| Body field | Type | Default | Rules |
|---|---|---|---|
| `blocked_by_ids` | `string[]` | required | 1–50 entries, each a **version-4** UUID. Duplicates are collapsed. Unknown keys are rejected with `400`. |

- **Any UUID casing is accepted**; the server canonicalizes to lowercase, so responses and
  activity entries always carry lowercase ids. Two casings of one id count as a single entry.
- **`Content-Type: application/json` is required, including on DELETE.** Without it the body is
  ignored and you get the "missing `blocked_by_ids`" 400 (§5). axios sets the header for
  `delete(url, { data })`; with `fetch`, set it yourself.
- Mock ids such as `11111111-1111-1111-1111-111111111111` are not version 4 and are rejected.
  Generate fixtures with `crypto.randomUUID()`.

```bash
curl -X POST "http://localhost:1996/api/tasks/$TASK_ID/dependencies" \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --header 'Content-Type: application/json' \
  --data '{"blocked_by_ids":["9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b"]}'
```

Equivalent HTTP request (DELETE has the same shape):

```http
POST /api/tasks/33333333-3333-4333-8333-333333333333/dependencies HTTP/1.1
Host: localhost:1996
Authorization: Bearer <access_token>
Content-Type: application/json
Accept: application/json

{"blocked_by_ids":["9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b"]}
```

---

## 2. Behavior

A dependency is a directed edge: **X blocks T**, or equivalently **T is blocked by X**. Every route
is addressed by the **blocked** task and edits only that task's `blocked_by` list:

| UI action, on the task `T` being viewed | Request |
|---|---|
| "T is blocked by X" | `POST /api/tasks/{T}/dependencies` `{ "blocked_by_ids": ["X"] }` |
| "T blocks Y" | `POST /api/tasks/{Y}/dependencies` `{ "blocked_by_ids": ["T"] }` |
| Remove "T is blocked by X" | `DELETE /api/tasks/{T}/dependencies` `{ "blocked_by_ids": ["X"] }` |
| Remove "T blocks Y" | `DELETE /api/tasks/{Y}/dependencies` `{ "blocked_by_ids": ["T"] }` |

`GET` on either task shows the edge from its side: after "X blocks T", `X` appears in
`T.blocked_by` and `T` appears in `X.blocks`.

**POST (add):**

- **All-or-nothing.** If any id is unknown, deleted, in another project, or would create a cycle,
  the whole request is rejected and nothing is written.
- **Idempotent.** Ids that already block the task are skipped silently. The response is still
  `201` with the full current view, even when nothing new was added.
- **Cycles are checked across the whole graph**, not only direct links. With `KAN-1 blocks KAN-2`
  and `KAN-2 blocks KAN-3`, "KAN-3 blocks KAN-1" returns
  `409 Adding "KAN-3" would create a dependency cycle`. A task in its own `blocked_by_ids` returns
  `409 A task cannot block itself`.
- Any task in the same project can be linked, including subtasks and tasks in archived columns.

**DELETE (remove):**

- **Idempotent.** Ids that are not current blockers, including unknown ids, are ignored. A member
  always gets `204`.
- The blockers are not project-checked, so an edge whose blocker has since moved to another
  project can still be removed.

**Not in this release** (tracked separately, so don't build against them): a blocked task can
still move to `done`; there are no Socket.IO events for dependency changes; dependencies are not
embedded in task, board or search payloads; cross-project dependencies are not allowed.

---

## 3. Successful response

`GET` (`200`) and `POST` (`201`) return the same body: the current view of the route task.
`DELETE` returns `204` with an empty body.

```json
{
  "blocked_by": [
    {
      "id": "9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b",
      "ticket_id": "KAN-12",
      "title": "Design schema",
      "status": "in_progress",
      "column_id": 3
    }
  ],
  "blocks": [
    {
      "id": "7d4e5f60-2a3b-4c5d-8e9f-0a1b2c3d4e5f",
      "ticket_id": "KAN-19",
      "title": "Ship the API",
      "status": "open",
      "column_id": 1
    }
  ]
}
```

### Typed payload

```ts
export type TaskStatus = 'open' | 'in_progress' | 'in_review' | 'done' | 'cancelled';

export interface DependencyTask {
  id: string;
  ticket_id: string | null;
  title: string;
  status: TaskStatus;
  column_id: number;
}

export interface TaskDependencies {
  blocked_by: DependencyTask[]; // tasks that block this task
  blocks: DependencyTask[];     // tasks this task blocks
}

export interface ManageDependenciesBody {
  blocked_by_ids: string[]; // 1–50 version-4 UUIDs, as returned by the API
}

export interface ApiErrorBody {
  statusCode: number;
  message: string | string[];
  error?: string;
}
```

Both arrays are always present (possibly `[]`) and ordered by ticket number ascending. There is
**no pagination**. Each entry is a compact summary, not a full task: there is no `project_id`,
priority, description, assignees or labels. Use `id` as the list key and to navigate to the task;
treat `column_id` as display data (see §4).

---

## 4. Activity entries & consistency

Changes that actually add or remove an edge are recorded on the **blocked** (route) task. They
appear in `GET /api/tasks/{id}/activities` (paginated, oldest first) and can be filtered with
`?action=task_dependency_added` or `?action=task_dependency_removed`:

```json
{
  "data": [
    {
      "id": "5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d",
      "actor": {
        "id": "0b1c2d3e-4f5a-4b6c-9d7e-8f9a0b1c2d3e",
        "email": "an.nguyen@example.com",
        "full_name": "An Nguyen",
        "role": "backend_developer",
        "avatar_url": "https://api.dicebear.com/9.x/initials/svg?seed=AN",
        "is_active": true,
        "created_at": "2026-09-01T02:00:00.000Z",
        "updated_at": "2026-09-01T02:00:00.000Z"
      },
      "action": "task_dependency_added",
      "payload": {
        "dependencies": [
          { "task_id": "9f1c2b3a-1d2e-4f50-9a6b-7c8d9e0f1a2b", "ticket_id": "KAN-12", "title": "Design schema" }
        ]
      },
      "created_at": "2026-09-25T08:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 1, "totalPages": 1 }
}
```

```ts
export type UserRole =
  | 'backend_developer' | 'frontend_developer' | 'fullstack_developer'
  | 'qa' | 'devops' | 'designer' | 'product_manager' | 'tech_lead';

export interface DependencyActivity {
  id: string;
  actor: {
    id: string; email: string; full_name: string; role: UserRole;
    avatar_url: string; is_active: boolean; created_at: string; updated_at: string;
  } | null;
  action: 'task_dependency_added' | 'task_dependency_removed';
  payload: {
    dependencies: Array<{
      task_id: string;
      ticket_id: string | null;
      title: string | null; // null on a removal whose blocker had left the project
    }>;
  };
  created_at: string;
}
```

- A POST where every id was already linked, or a DELETE that matched nothing, records no activity.
  The blockers' own feeds get no entry.
- Activity is written **asynchronously**, so a refetch right after the `201`/`204` may not show
  the new entry yet.
- **Edges survive project moves.** If either task is later moved to another project
  (`PATCH /tasks/{id}/move`), the edge is kept. An entry's `column_id` may then belong to another
  board, and opening that task can return `404`. Don't assume every entry is on the current board.
- Deleting a task deletes its edges: it drops out of other tasks' lists, and no activity is recorded.
- Other users' changes are not pushed to the client. They show up only on refetch.

---

## 5. Error semantics

Checks run in this order; the first failure wins.

| # | Status | Situation | Frontend handling |
|---|---|---|---|
| 1 | `401` | Missing/invalid/expired token, deleted or inactive user | Normal HTTP refresh/login flow. |
| 2 | `400` | `{id}` is not a UUID | Caller bug; don't retry. |
| 3 | `400` | Body fails validation | Prevent in the UI; don't show the raw messages. |
| 4 | `404` | Task `{id}` does not exist, **or the caller is not a project member** | Show the task's not-found state. |
| 5 | `403` | Caller is a `viewer` (POST/DELETE only) | Hide the controls for viewers; show "member access needed". |
| 6 | `404` | A `blocked_by_ids` entry is unknown, deleted, or in another project (POST only) | The selection is stale: refetch the list and the picker source. |
| 7 | `409` | Self-reference, or the edge would close a cycle (POST only) | Show the server `message` as-is; it is user-presentable. |
| — | `500` | Unexpected server failure | Show a retryable error. |

Validation failure (here: the body is `{}`):

```json
{
  "message": [
    "blocked_by_ids must contain no more than 50 elements",
    "blocked_by_ids should not be empty",
    "blocked_by_ids must be an array"
  ],
  "error": "Bad Request",
  "statusCode": 400
}
```

| Body sent | `message` |
|---|---|
| `{}`, no body, no JSON `Content-Type`, or `{ "blocked_by_ids": "abc" }` | the three messages above |
| `{ "blocked_by_ids": [] }` | `["blocked_by_ids should not be empty"]` |
| `{ "blocked_by_ids": ["abc"] }`, or a non-v4 UUID | `["each value in blocked_by_ids must be a UUID"]` |
| 51 ids | `["blocked_by_ids must contain no more than 50 elements"]` |
| `{ "blockedByIds": [...] }` | `["property blockedByIds should not exist"]` followed by the three messages above |

A *missing* array really does report "no more than 50 elements"; the validation engine does this.
Unknown keys are listed first. Malformed JSON returns a single parser message string; don't
display it.

Other bodies, verbatim:

| # | Body |
|---|---|
| 1 | `{ "message": "Unauthorized", "statusCode": 401 }` |
| 2 | `{ "message": "Validation failed (uuid is expected)", "error": "Bad Request", "statusCode": 400 }` |
| 4, 6 | `{ "statusCode": 404, "message": "Task with id \"33333333-3333-4333-8333-333333333333\" not found" }` |
| 5 | `{ "statusCode": 403, "message": "This action requires at least member role" }` |
| 7 | `{ "statusCode": 409, "message": "Adding \"KAN-12\" would create a dependency cycle" }` or `{ "statusCode": 409, "message": "A task cannot block itself" }` |
| — | `{ "statusCode": 500, "message": "Internal server error" }` |

The 404, 403 and 409 bodies have **no `error` field**. The 409 names the blocker by `ticket_id`, or
by its UUID when it has none. If several requested blockers would each close a cycle, it names one
of them (which one is unspecified); self-reference takes precedence. Cases #4 and #6 return **the
same 404 body** by design: the server never reveals whether a task exists in a project the caller
cannot see. The quoted id names the one that failed. Don't parse it for control flow; refetch `GET`
to tell whether the route task itself is gone.

**Not errors:** a POST whose ids are all already linked (`201`); a DELETE with unlinked or unknown
ids (`204`); a viewer calling `GET` (`200`).

---

## 6. Frontend integration rules

- **Cache key:** `['dependencies', taskId]`. After a POST, write the `201` body into the route
  task's key and invalidate each blocker's key; their `blocks` lists changed too. After a DELETE
  there is no body, so invalidate the route task and each blocker. You can also filter
  optimistically in `onMutate`.
- **Keep summaries fresh.** Entries embed `title`, `status` and `column_id`. After your own task
  mutations (status/title change, move, delete), invalidate `['dependencies']`. Only mounted lists
  refetch; the rest are marked stale. Clear these queries on logout, like other task data.
- **Picking blockers:** offer only tasks from the **same project**. The loaded board works. If you
  use `GET /api/search/tasks`, filter by `project_id`: search spans all the user's projects, and
  cross-project ids get a `404`. Exclude the task itself and ids already in `blocked_by`.
  Excluding `blocks` also avoids the obvious direct cycle; leave longer cycles to the server's
  `409`.
- **Cap the selection at 50** per request. Splitting a larger selection into several requests
  loses the all-or-nothing guarantee.
- **Hide add/remove controls for viewers.** The server still enforces the role (`403`).
- **Load dependencies in the task detail view only.** They aren't in board or task payloads, so
  don't issue one `GET` per board card.
- A "blocked" badge is a client-side rule (for example, any `blocked_by` entry whose status is not
  `done` or `cancelled`). The server defines none.

Reference hooks (TanStack Query v5; `api` is your axios instance with `baseURL`
`http://localhost:1996/api` and the bearer-token interceptor):

```ts
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export const dependencyKeys = {
  all: ['dependencies'] as const,
  task: (taskId: string) => ['dependencies', taskId] as const,
};

export const useTaskDependencies = (taskId: string) =>
  useQuery({
    queryKey: dependencyKeys.task(taskId),
    queryFn: () => api.get<TaskDependencies>(`/tasks/${taskId}/dependencies`).then((r) => r.data),
  });

/** For "T blocks Y", call useAddDependencies(Y).mutate([T]). */
export function useAddDependencies(taskId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ids: string[]) =>
      api.post<TaskDependencies>(`/tasks/${taskId}/dependencies`, { blocked_by_ids: ids })
        .then((r) => r.data),
    onSuccess: (view, ids) => {
      qc.setQueryData(dependencyKeys.task(taskId), view);
      ids.forEach((id) => qc.invalidateQueries({ queryKey: dependencyKeys.task(id) }));
    },
  });
}

export function useRemoveDependencies(taskId: string) {
  const qc = useQueryClient();
  return useMutation({
    // DELETE carries a JSON body: with axios it goes in `data`.
    mutationFn: (ids: string[]) =>
      api.delete(`/tasks/${taskId}/dependencies`, { data: { blocked_by_ids: ids } }),
    onSettled: (_res, _err, ids) =>
      [taskId, ...ids].forEach((id) => qc.invalidateQueries({ queryKey: dependencyKeys.task(id) })),
  });
}
```

---

## 7. Backend references

- [DependencyController](../../src/main/java/com/kanban/modules/dependency/DependencyController.java): routes, status codes, auth.
- [ManageDependenciesDto](../../src/main/java/com/kanban/modules/dependency/dto/ManageDependenciesDto.java): request validation.
- [TaskDependenciesResponseDto](../../src/main/java/com/kanban/modules/dependency/dto/TaskDependenciesResponseDto.java) and [TaskSummaryDto](../../src/main/java/com/kanban/modules/dependency/dto/TaskSummaryDto.java): response shape.
- [DependencyService](../../src/main/java/com/kanban/modules/dependency/DependencyService.java): role gates, masked 404, cycle check, idempotency, activity payloads.
- [DependencyRepository](../../src/main/java/com/kanban/modules/dependency/DependencyRepository.java) and [JpaDependencyQueries](../../src/main/java/com/kanban/modules/dependency/JpaDependencyQueries.java): list ordering, reachability walk.
- [TaskDependency](../../src/main/java/com/kanban/modules/dependency/TaskDependency.java) and [V5__create_task_dependencies.sql](../../src/main/resources/db/migration/V5__create_task_dependencies.sql): edge table, cascade on task delete.
- [ProjectAccessService](../../src/main/java/com/kanban/modules/project/ProjectAccessService.java): the task-flavored 404 and the 403 body.
- [Activity](../../src/main/java/com/kanban/modules/activity/Activity.java): activity entry shape.
- [DependencyServiceTest](../../src/test/java/com/kanban/modules/dependency/DependencyServiceTest.java) and [WebLayerTest](../../src/test/java/com/kanban/WebLayerTest.java) (JSP-33 section): behavior and wire-format checks.
