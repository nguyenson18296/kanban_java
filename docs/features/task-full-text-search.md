# Task Full-Text Search

Search tasks by the words in their **title or description**, across every project you belong to:
`GET /api/search/tasks`, built in Linear ticket
[JAV-34](https://linear.app/java-son-182/issue/JSP-34/backend-full-text-search-for-tasks) (now
shown as JSP-34). It also rewired the board's `search` filter onto the same fast index. Deep
details live in [docs/queries/search.md](../queries/search.md) and the
[frontend contract](../api-contracts/task-search.md).

## 1. What It Does

Before JAV-34, search was `ILIKE '%term%'` on titles only, inside the board endpoint: it scanned
the whole `tasks` table on every request (a leading `%` can never use an index), ignored
descriptions, and had no ranking. Now:

- One endpoint searches titles **and** descriptions of tasks in all your projects.
- Results come best-match-first, each with a highlighted snippet showing where the match is.
- The board filter (`GET /api/board/{projectId}?search=`) reuses the same implementation.

Out of scope (own tickets): typo-tolerant/fuzzy search (JAV-36), searching comments, autocomplete.

## 2. How It Works

Code lives in `com.kanban.modules.search`. The flow of `SearchService.searchTasks`:

1. `SearchController` (`@JwtAuth`) receives `q`, `page`, `limit`, validated by `TaskSearchQueryDto`.
2. Blank `q` → empty page. Otherwise `ProjectAccessService.getProjectIdsForUser(userId)` loads the
   caller's project ids — **that list is the authorization**; no memberships → empty page.
3. `JpaTaskSearchQueries` (native SQL) counts the matches, then fetches one ranked page of hits
   (task id + project id + snippet).
4. `TaskRepository.findByIdInForUserWithAssigneesAndLabels` re-loads those tasks with assignees and
   labels, re-checking membership so access revoked mid-request never leaks a row.
5. Each hit is serialized as `task.toJson({assignees, labels})` plus `project_id` and `snippet`.

Key decisions, and why:

- **Postgres does the searching.** A generated column `tasks.search_vector` (a `tsvector` —
  Postgres' pre-chopped word list of title + description) is kept in sync by Postgres itself and
  served by a GIN index (an index type built for finding words). Measured on 10k seeded tasks:
  old query = full table scan, new = index scan.
- **`simple` text configuration:** task text mixes Vietnamese/English/Finnish, so no stemming and
  no stop-word removal — every word in every language is searchable. Trade-off: `login` does not
  match `logins`.
- **Pure relevance ranking:** title matches (weight A) beat description matches (weight B); ties
  broken by newest `updated_at`, then id, so pages stay stable.
- **One search implementation:** `TaskSearchSql` holds the shared SQL predicate; this endpoint and
  the board filter both use it, so queries always parse text the same way the column was built.

## 3. API

`GET /api/search/tasks` — `@JwtAuth`; any project member (viewer is enough). Returns
`{ data, meta: { page, limit, total, totalPages } }`.

| Param | Default | Rules |
|---|---|---|
| `q` | – | Max 200 chars. Websearch syntax: `"a phrase"`, `-excluded`, `or`. Blank → empty page. |
| `page` | 1 | Minimum 1 |
| `limit` | 20 | 1–100 |

Example hit, trimmed — full payload shapes and TypeScript types are in the
[frontend contract](../api-contracts/task-search.md):

```json
{
  "id": "22222222-2222-4222-8222-222222222222",
  "title": "Fix login page",
  "assignees": [], "labels": [],
  "project_id": "UrzWUH3e",
  "snippet": "Fix <mark>login</mark> page …"
}
```

| Status | When |
|---|---|
| 400 | Invalid or unknown params (`limit=101`, `projectId=…`) |
| 401 | Missing/invalid token |
| 200, empty `data` | Blank `q`, no memberships, or no matches — never a 403/404 |

Board filter: `GET /api/board/{projectId}?search=` (`@RequireProjectRole(VIEWER)`) — same word
matching since JAV-34 (was substring matching); response shape unchanged.

## 4. Database

No new tables — `V4__add_task_search_vector.sql` added one column and one index:

```sql
ALTER TABLE tasks ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('simple', title), 'A') ||
  setweight(to_tsvector('simple', coalesce(description, '')), 'B')
) STORED;

CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector) WITH (fastupdate = off);
```

Tasks have no `project_id` column, so queries find a task's project through its column
(`tasks.column_id → kanban_columns.project_id`). Every query the feature runs, with rationale
per query: [docs/queries/search.md](../queries/search.md) (board: [board.md](../queries/board.md)).

## 5. Security

- JWT required. The caller's membership list scopes every query: tasks in other projects are never
  returned **or counted**, and an outsider just gets an empty page — nothing leaks.
- The entity load (step 4 above) re-checks membership at that moment, so a task moved away or an
  access revoked between the two queries is dropped, not serialized.
- All user input is bound as SQL parameters — no SQL injection; `websearch_to_tsquery` accepts any
  characters safely.
- The 200-char cap on `q` (and the board's `search`) is load-bearing: a single word of 2047+ bytes
  would make Postgres itself error (a 500 instead of a clean validation 400).
- Snippets are HTML-escaped **before** `<mark>` is inserted, so `<mark>` is the only tag they can
  contain — safe to render, no XSS.
- `Recommended improvement`: no rate limit on search yet (only login has one, JAV-37).

## 6. Testing

- `SearchServiceTest` — empty-page shortcuts, ranked order, snippet escaping, pagination and
  overflow, the membership re-check. Run: `mvn test -Dtest=SearchServiceTest`
- `WebLayerTest` — 401 body, the `{ data, meta }` wire shape, `limit=101` → 400.
- `BoardServiceTest` — blank board search means no filter; search text passes through untouched.
- Not covered: nothing runs the real SQL against PostgreSQL — the query plans were verified
  manually with `EXPLAIN ANALYZE` for the PR.

## 7. Gotchas & Limitations

- The text configuration is pinned in **two places that must change together**: the V4 column and
  `TaskSearchSql.CONFIG`. A mismatch does not error — it silently returns wrong or empty results.
  Change both via a **new** migration (never edit an applied one) in one PR.
- `login` ≠ `logins` (no stemming), and typos find nothing — fuzzy matching is deferred to JAV-36.
- Don't raise the `@MaxLength(200)` caps without re-reading the tsquery byte limit in §5.
- `meta.total` and the page rows are two separate reads: concurrent edits can shorten a page
  without changing its `total` (accepted; documented in the frontend contract).
- When changing behaviour, update in the same PR: `docs/queries/search.md`,
  `docs/api-contracts/task-search.md`, `CLAUDE.md`, and this file.

## 8. Where to Look Next

- Code: `src/main/java/com/kanban/modules/search/` and
  `TaskRepository.findByIdInForUserWithAssigneesAndLabels`
- SQL with per-query rationale: [docs/queries/search.md](../queries/search.md)
- Frontend contract (full payloads, integration rules): [docs/api-contracts/task-search.md](../api-contracts/task-search.md)
- Migration: `src/main/resources/db/migration/V4__add_task_search_vector.sql`
- Tickets: [JAV-34 / JSP-34](https://linear.app/java-son-182/issue/JSP-34/backend-full-text-search-for-tasks); fuzzy follow-up JAV-36
