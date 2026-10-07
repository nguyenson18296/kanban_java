# Project dashboard contract — frontend integration

HTTP contract for the per-project task statistics dashboard (JSP-44), matching the current backend
implementation. Use this alongside Swagger (`/api/docs`); the SQL, cache keys and measurements are
documented in [dashboard.md](../queries/dashboard.md).

| | |
|---|---|
| Endpoint | `GET /api/projects/{projectId}/dashboard` |
| Base URL | Default `http://localhost:1996/api` |
| Auth | `Authorization: Bearer <access_token>`; any project member (viewer and up) |
| Scope | One project: every task **and subtask** in its non-archived columns |
| Response | The statistics object itself (no `{ data }` envelope) |
| Casing | **snake_case** keys |
| Freshness | May be served from a short-lived server cache; `computed_at` says when the numbers were computed |

**Changelog**

- 2026-10-07 (JSP-44): documented the dashboard endpoint, counting rules, payload, freshness
  guarantees, errors and frontend integration rules.

---

## 1. Request & authentication

Use the `access_token` returned by login or token refresh. Missing, invalid or expired tokens,
and deleted or inactive users, receive `401`.

| Path parameter | Type | Rules |
|---|---|---|
| `projectId` | string | The project id (8 alphanumeric characters, e.g. `UrzWUH3e`). |

There is no query string and no body. There are no filters, date ranges or history in v1.

The caller's membership is checked on **every** request, before any cached numbers are read: a
user removed from the project gets `404` on the very next request, even if the numbers are cached.

```bash
curl 'http://localhost:1996/api/projects/UrzWUH3e/dashboard' \
  --header "Authorization: Bearer $ACCESS_TOKEN"
```

Equivalent HTTP request:

```http
GET /api/projects/UrzWUH3e/dashboard HTTP/1.1
Host: localhost:1996
Authorization: Bearer <access_token>
Accept: application/json
```

---

## 2. What is counted

Show these rules next to the dashboard (for example as a tooltip) so the numbers are not misread.

| Rule | Meaning |
|---|---|
| Counted set | Every task **and every subtask** whose column belongs to the project and is **not archived**. A subtask is its own unit, counted in its own column. |
| `total_tasks` | Size of the counted set. Equals the sum of `by_status`. |
| `by_status` | Count per task status. All five statuses are always present, `0` when empty. Statuses come from the task's `status` field, never from column names. |
| `overdue_tasks` | Tasks with a `due_date` strictly before `computed_at` whose status is neither `done` nor `cancelled`. Tasks without a due date are never overdue; a task due exactly at `computed_at` is not overdue yet. |
| `by_assignee` | One entry per user with at least one counted task. A task with several assignees counts **once for each** of them, so the sum of `task_count` can exceed `total_tasks`. Ordered by `task_count` descending, then `user_id` ascending. |
| `unassigned_tasks` | Counted tasks with no assignee. |
| `computed_at` | The single instant every number above refers to (also the overdue cut-off). |

Not counted: tasks in archived columns and tasks of other projects. Labels, priorities, teams and
comments play no part.

---

## 3. Successful response

Example `200 OK`:

```json
{
  "project_id": "UrzWUH3e",
  "total_tasks": 12,
  "overdue_tasks": 2,
  "by_status": {
    "open": 5,
    "in_progress": 3,
    "in_review": 1,
    "done": 2,
    "cancelled": 1
  },
  "by_assignee": [
    { "user_id": "22222222-2222-4222-8222-222222222222", "task_count": 4 },
    { "user_id": "33333333-3333-4333-8333-333333333333", "task_count": 4 }
  ],
  "unassigned_tasks": 3,
  "computed_at": "2026-10-06T07:00:00.000Z"
}
```

### Typed payload

```ts
export type TaskStatus = 'open' | 'in_progress' | 'in_review' | 'done' | 'cancelled';

export interface DashboardAssigneeCount {
  user_id: string;    // users.id (uuid). Resolve the name via the project members list.
  task_count: number; // >= 1
}

export interface ProjectDashboard {
  project_id: string;
  total_tasks: number;
  overdue_tasks: number;
  by_status: Record<TaskStatus, number>; // every key always present
  by_assignee: DashboardAssigneeCount[];  // possibly empty, never null
  unassigned_tasks: number;
  computed_at: string;                    // ISO 8601 UTC with milliseconds
}

export interface ApiErrorBody {
  statusCode: number;
  message: string | string[];
  error?: string;
}
```

Every field is always present and never `null`. `by_assignee` carries ids only: resolve names
and avatars by joining with `GET /api/projects/{projectId}/members` (`data[].user_id`,
`data[].user.full_name`). A `user_id` missing from that list belongs to someone who left the
project but still has tasks assigned — show a fallback such as "Former member".

---

## 4. Empty project & freshness

A project with no counted task (no columns, only empty columns, or only archived columns) is a
normal `200`, not an error:

```json
{
  "project_id": "UrzWUH3e",
  "total_tasks": 0,
  "overdue_tasks": 0,
  "by_status": { "open": 0, "in_progress": 0, "in_review": 0, "done": 0, "cancelled": 0 },
  "by_assignee": [],
  "unassigned_tasks": 0,
  "computed_at": "2026-10-06T07:00:00.000Z"
}
```

**Freshness.** The numbers are one consistent snapshot, but they can be up to the server cache
lifetime old (default 60 seconds, configured by the backend):

- Task and column changes made through this API (create/delete task or subtask, status, due date,
  assignees, moving a task or a column) drop the cached numbers before the write's response
  returns, so a refetch after a successful write normally shows the change.
- Two cases stay stale until the cache expires: a refetch racing with someone else's write, and
  overdue counts while time passes with no write.
- When numbers come from the cache, `computed_at` keeps the time they were computed; it is not the
  time of your request.

The response is identical whether it came from the cache or was computed fresh; the frontend must
not try to tell them apart.

---

## 5. Error semantics

| Status | Situation | Frontend handling |
|---|---|---|
| `401` | Missing/invalid/expired token, deleted or inactive user | Use the normal HTTP refresh/login flow. |
| `404` | Project does not exist, the caller is not a member (anymore), or the id is malformed | Treat as "no access": leave the dashboard, refresh the project list. |
| `500` | Unexpected server failure | Show a retryable error state. |

Authentication failure:

```json
{ "message": "Unauthorized", "statusCode": 401 }
```

Not a member, unknown project or malformed id (the same body, so it reveals nothing):

```json
{ "statusCode": 404, "message": "Project with id \"UrzWUH3e\" not found" }
```

Unexpected failure:

```json
{ "statusCode": 500, "message": "Internal server error" }
```

There is no `403`: every project role may read the dashboard. A server cache outage is not an
error either — the backend falls back to computing the numbers.

---

## 6. Frontend integration rules

- Cache key: `['projects', projectId, 'dashboard']` (plus the user, cleared on logout). Never reuse
  one project's data for another: on project switch show the loading state, not the previous
  project's numbers (no `keepPreviousData`/`placeholderData` across project ids), and drop a late
  response whose `project_id` is not the current project.
- Show `computed_at` as "Updated at …" (local time). Do not show cache or debug details.
- Refetch (invalidate the key) after the user's own task/column writes succeed. There is no
  Socket.IO event for dashboard or task changes; other users' changes appear on the next fetch
  (e.g. on window focus or when the dashboard is reopened).
- Polling, if any, should not be faster than the cache lifetime (60 s by default); faster polls
  mostly return the same snapshot.
- Loading, empty (`total_tasks === 0`) and error states are all needed; empty is not an error.
- Render `by_status` in the fixed order `open, in_progress, in_review, done, cancelled`; keep the
  server's `by_assignee` order and show `unassigned_tasks` as its own row.

---

## 7. Backend references

- [DashboardController](../../src/main/java/com/kanban/modules/dashboard/DashboardController.java): route, auth and the VIEWER gate.
- [DashboardResponse](../../src/main/java/com/kanban/modules/dashboard/dto/DashboardResponse.java): the payload.
- [DashboardService](../../src/main/java/com/kanban/modules/dashboard/DashboardService.java): cache-aside flow and response assembly.
- [JpaDashboardQueries](../../src/main/java/com/kanban/modules/dashboard/JpaDashboardQueries.java): the counting SQL.
- [DashboardCache](../../src/main/java/com/kanban/modules/dashboard/DashboardCache.java): TTL, fallback and eviction.
- [WebLayerTest](../../src/test/java/com/kanban/WebLayerTest.java) pins the 200/401/404 wire format; [DashboardHttpIT](../../src/test/java/com/kanban/modules/dashboard/DashboardHttpIT.java) and [DashboardQueriesIT](../../src/test/java/com/kanban/modules/dashboard/DashboardQueriesIT.java) pin the behavior above against PostgreSQL and Redis.
