# Task search contract — frontend integration

HTTP contract for cross-project task search (JAV-34), matching the current backend implementation.
Use this alongside Swagger (`/api/docs`); database queries are documented in
[search.md](../queries/search.md). Real-time events have their own [socket contract](socket-events.md).

| | |
|---|---|
| Endpoint | `GET /api/search/tasks` |
| Base URL | Default `http://localhost:1996/api` |
| Auth | `Authorization: Bearer <access_token>` |
| Scope | Every project the authenticated user belongs to, including viewer membership |
| Searches | Task title and description |
| Response | JSON `{ data, meta: { page, limit, total, totalPages } }` |
| Casing | Task fields use **snake_case**; pagination uses **`totalPages`** |

**Changelog**

- 2026-09-14 (JAV-34): documented the implemented search endpoint, payloads, search syntax,
  pagination, errors, and frontend integration rules.

---

## 1. Request & authentication

Use the `access_token` returned by login or token refresh. Missing, invalid, or expired tokens,
and deleted or inactive users, receive `401` — including requests with a blank search.

| Query parameter | Type | Default | Rules |
|---|---|---|---|
| `q` | string | omitted | Optional; maximum 200 Unicode code points, checked **before** trimming. Leading/trailing whitespace is stripped. Omitted, empty, or whitespace-only input returns an empty page. |
| `page` | integer | `1` | One-based, minimum `1`. |
| `limit` | integer | `20` | Minimum `1`, maximum `100`. |

Send each parameter once. Unknown parameters are rejected with `400`, including `search`,
`projectId`, `priority`, `assigneeId`, `labelId`, and `sort`. This endpoint accepts only `q`,
`page`, and `limit`; project scope comes from the authenticated user's memberships.

```bash
curl --get 'http://localhost:1996/api/search/tasks' \
  --header "Authorization: Bearer $ACCESS_TOKEN" \
  --data-urlencode 'q=login page' \
  --data-urlencode 'page=1' \
  --data-urlencode 'limit=20'
```

Equivalent HTTP request:

```http
GET /api/search/tasks?q=login%20page&page=1&limit=20 HTTP/1.1
Host: localhost:1996
Authorization: Bearer <access_token>
Accept: application/json
```

---

## 2. Search behavior & ordering

Matching uses whole words in titles and descriptions, with case normalization, no stemming,
and no stop-word removal. `login` does not match `logins` or the substring `log`.
There is no fuzzy/typo-tolerant matching. Ticket IDs, comments, assignee names, and label names
are not search fields.

| `q` value (before URL encoding) | Meaning |
|---|---|
| `login page` | Both words must match; they need not be adjacent. |
| `"login page"` | Match the phrase in that order. |
| `login or signup` | Either word may match. |
| `login -mobile` | Match `login`, excluding tasks containing `mobile`. |
| `-bug` | Match tasks without `bug`; these results have no positive-match relevance score. |
| `!!!` | No searchable terms → empty page. |

Unbalanced quotes and other unusual search syntax do not by themselves cause a validation
error. Always URL-encode the query value; do not construct a URL by concatenating raw user text.

Results are ordered by relevance descending, then `updated_at` descending, then task ID
ascending. Title matches have more weight than description matches; the relevance score is
internal and is **not returned**. Preserve the server's result order.

Tasks in archived columns and subtasks are included. Tasks in projects the caller does not
belong to are excluded from both results and counts. A user with no project memberships
receives `200` with an empty page.

---

## 3. Successful response

Example `200 OK` for `q=login&page=1&limit=20` with one matching task. IDs and timestamps are
illustrative; snippet wording can vary with the matching text.

```json
{
  "data": [
    {
      "id": "22222222-2222-4222-8222-222222222222",
      "title": "Fix login page",
      "description": "Show a helpful error when credentials are invalid.",
      "status": "open",
      "priority": "high",
      "position": 10,
      "ticket_id": "KAN-42",
      "ticket_number": 42,
      "column_id": 1,
      "team_id": null,
      "assignees": [],
      "labels": [],
      "due_date": null,
      "created_at": "2026-09-12T08:00:00Z",
      "updated_at": "2026-09-14T09:30:00Z",
      "parent_id": null,
      "project_id": "UrzWUH3e",
      "snippet": "Fix <mark>login</mark> page Show a helpful error when credentials are invalid."
    }
  ],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 1,
    "totalPages": 1
  }
}
```

### Typed payload

```ts
export type TaskStatus = 'open' | 'in_progress' | 'in_review' | 'done' | 'cancelled';
export type TaskPriority = 'no_priority' | 'urgent' | 'high' | 'medium' | 'low';
export type UserRole =
  | 'backend_developer' | 'frontend_developer' | 'fullstack_developer'
  | 'qa' | 'devops' | 'designer' | 'product_manager' | 'tech_lead';

export interface SearchAssignee {
  id: string;
  email: string;
  full_name: string;
  role: UserRole; // User's job role, not their project membership role.
  avatar_url: string;
  is_active: boolean;
  created_at: string; // ISO 8601 timestamp.
  updated_at: string;
}

export interface SearchLabel {
  id: number;
  name: string;
  color: string;
  created_at: string;
  updated_at: string;
}

export interface TaskSearchHit {
  id: string;
  title: string;
  description: string | null;
  status: TaskStatus;
  priority: TaskPriority;
  position: number;
  ticket_id: string | null;
  ticket_number: number | null;
  column_id: number;
  team_id: number | null;
  assignees: SearchAssignee[];
  labels: SearchLabel[];
  due_date: string | null;
  created_at: string;
  updated_at: string;
  parent_id: string | null;
  parent?: { id: string }; // Present for a subtask; omitted for a top-level task.
  project_id: string;
  snippet: string; // Server-produced HTML fragment containing <mark> highlights.
}

export interface TaskSearchResponse {
  data: TaskSearchHit[];
  meta: {
    page: number;
    limit: number;
    total: number;
    totalPages: number;
  };
}

export interface TaskSearchParams {
  q?: string;
  page?: number;
  limit?: number;
}

export interface ApiErrorBody {
  statusCode: number;
  message: string | string[];
  error?: string;
}
```

`assignees` and `labels` are always arrays, including when empty. Nullable fields are returned
as `null`. Search does not return `creator`, `created_by`, `subtasks`, a full parent record,
project details, or a relevance score. Use `project_id`, `column_id`, and `id` to navigate to
the task; treat IDs as opaque values.

### Snippet rendering

`snippet` combines excerpts from the title and description. The server HTML-escapes text and
inserts `<mark>...</mark>` around matches. Render this field as the server-provided HTML
fragment to display highlights; render `title` and `description` as ordinary text.

A snippet can contain multiple excerpts joined by ` ... `, or no highlights (for example,
a negation-only query). Do not rely on a fixed length or use the snippet as the full description.

---

## 4. Pagination & empty states

Pagination applies to the combined result set across all authorized projects, **not per column**.
`total` counts all matching tasks; `totalPages = ceil(total / limit)`.

Missing/blank `q`, no memberships, or no matches with default pagination:

```json
{
  "data": [],
  "meta": { "page": 1, "limit": 20, "total": 0, "totalPages": 0 }
}
```

A page beyond the last page is successful and preserves the requested page and actual count.
For example, page 4 with limit 10 when there are 25 matches:

```json
{
  "data": [],
  "meta": { "page": 4, "limit": 10, "total": 25, "totalPages": 3 }
}
```

The count and task load are separate reads. Concurrent deletion, movement, or membership
changes can shorten a page without changing its already-computed `total`. A task moved between
two authorized projects during the request can carry the `project_id` from the earlier search
read. Refetch when navigation finds changed data; results are not a snapshot across requests.

---

## 5. Error semantics

| Status | Situation | Frontend handling |
|---|---|---|
| `400` | Invalid query parameters or unknown keys | Correct the request; show validation messages. |
| `401` | Missing/invalid/expired token, deleted or inactive user | Use the normal HTTP refresh/login flow. |
| `500` | Unexpected server failure | Show a retryable search error. |

For `limit=101`:

```json
{
  "message": ["limit must not be greater than 100"],
  "error": "Bad Request",
  "statusCode": 400
}
```

Other validation messages include `page must not be less than 1`,
`q must be shorter than or equal to 200 characters`, and
`property projectId should not exist`. A request can return multiple validation messages.

Authentication failure:

```json
{ "message": "Unauthorized", "statusCode": 401 }
```

Unexpected failure:

```json
{ "statusCode": 500, "message": "Internal server error" }
```

Lack of project membership is an empty result, not a `403` or project `404` for this endpoint.

---

## 6. Frontend integration rules

- Use `q` for this endpoint. The board endpoint uses a different parameter, `search`.
- Reset `page` to `1` when the query or page size changes. Preserve response order.
- For typeahead, debounce input and cancel or ignore obsolete requests so an older response
  cannot replace results for the current query.
- Include the authenticated user, query, page, and limit in the search cache key. Clear cached
  results on logout/account changes; refetch after relevant task or membership changes.
- An empty input can show an idle search state locally; the server returns an empty page if called.
- Use `meta.page < meta.totalPages` to determine whether another page is available.

### Search endpoint versus board filter

| | Task search | Board filter |
|---|---|---|
| Request | `GET /api/search/tasks?q=login` | `GET /api/board/{projectId}?search=login` |
| Scope | All caller memberships | One project |
| Ordering | Relevance, update time, ID | Position within each column |
| Limit | A page of results across projects | Top N tasks per column |
| Highlighted snippet | Yes | No |
| Blank search | Empty search page | Board without a search filter |

Both use the same word-matching rules. Board-only filters cannot be passed to task search.

---

## 7. Backend references

- [SearchController](../../src/main/java/com/kanban/modules/search/SearchController.java): route and auth.
- [TaskSearchQueryDto](../../src/main/java/com/kanban/modules/search/dto/TaskSearchQueryDto.java): accepted parameters and validation.
- [SearchService](../../src/main/java/com/kanban/modules/search/SearchService.java): scope, empty pages, result fields, and snippet escaping.
- [JpaTaskSearchQueries](../../src/main/java/com/kanban/modules/search/JpaTaskSearchQueries.java): ranking and pagination.
- [Task](../../src/main/java/com/kanban/modules/task/Task.java), [User](../../src/main/java/com/kanban/modules/user/User.java), and [Label](../../src/main/java/com/kanban/modules/label/Label.java): serialized payloads.
- [SearchServiceTest](../../src/test/java/com/kanban/modules/search/SearchServiceTest.java): existing behavior checks.
