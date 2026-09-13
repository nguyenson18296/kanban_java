# Search — raw PostgreSQL queries

Frontend request/response contract: [Task search](../api-contracts/task-search.md).

Queries executed by `modules/search` for `GET /search/tasks` (`SearchController`, `SearchService`,
`TaskSearchQueries` / `JpaTaskSearchQueries`) — PostgreSQL full-text search over task titles
**and** descriptions across every project the caller belongs to (JAV-34). The route is `@JwtAuth`
only; **the caller's membership list is the authorization**: `SearchService` loads the caller's
project ids ([project.md](project.md) query 3) and every query below is scoped to them, so tasks
in other projects are never returned or counted. No memberships, or a blank `q`, → an empty page
(`{ data: [], meta: { total: 0 } }`) without touching `tasks`.

The board filter (`GET /board/{projectId}?search=`) uses the **same predicate** through the shared
`TaskSearchSql` constants — see [board.md](board.md) — so there is exactly one search
implementation and both sides parse input with the configuration the column was built with.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (aliases and column lists simplified so it
  pastes into `psql`). Queries 2 and 3 are verbatim native SQL; `@EntityGraph` relation loads are
  noted, not expanded into JOINs.
- Parameters are `:name`. Example values: project ids `('UrzWUH3e', 'Ab3dEf9h')`,
  `search = 'login page'`, `limit = 20`, `offset = 0`.

## Schema — `V4__add_task_search_vector.sql`

```sql
ALTER TABLE tasks ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('simple', title), 'A') ||
  setweight(to_tsvector('simple', coalesce(description, '')), 'B')
) STORED;

CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector) WITH (fastupdate = off);
```

- Postgres recomputes the column on every `INSERT`/`UPDATE`; the `Task` entity does not map it
  (Hibernate never selects `*`, and the position stored procedures only touch `position`).
- **Configuration `simple`** (decision, JAV-34): task text is mixed Vietnamese/English/Finnish.
  `simple` lowercases and splits on words with no stemming and no stop-word removal, so every word
  in every language is searchable and matching is predictable whole-word matching. `english`
  would stem only English and drop stop words (a search for "the" would find nothing) while doing
  nothing for Vietnamese. Trade-off: `login` does not match `logins`. The query side must use the
  same configuration — that is what `TaskSearchSql.CONFIG` pins.
- **Weights**: title `A` (1.0) outranks description `B` (0.4) in `ts_rank_cd`.
- **`fastupdate = off`**: GIN normally buffers new entries in a pending list that the planner
  costs pessimistically — right after the 10k-row benchmark load every search fell back to a
  sequential scan until `VACUUM` merged the list. With `fastupdate = off` entries go straight
  into the index; task writes are human-paced, so the extra per-row work is negligible.

## Gate per route

| Route | Gate before the queries below |
|---|---|
| `GET /search/tasks` | `@JwtAuth`; then query 1 (caller's project ids) scopes queries 2–3 — `[]` (or a blank `q`) short-circuits to an empty page |

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Caller's project ids | `ProjectAccessService.getProjectIdsForUser` → `ProjectMemberRepository.findProjectIdsByUserId` | every non-blank search (project.md query 3) |
| 2 | Total matching tasks | `JpaTaskSearchQueries.count` | every search with ≥ 1 membership (`meta.total`) |
| 3 | Ranked page of hits + headline | `JpaTaskSearchQueries.search` | same, unless the requested offset is already ≥ `total` (empty page, no query) |
| 4 | Tasks by id, re-scoped to the caller's current memberships, with assignees and labels | `TaskRepository.findByIdInForUserWithAssigneesAndLabels` | when query 3 returned rows (task.md query 9) |

## Queries

### 1. Caller's project ids

[project.md](project.md) query 3 — owners have a `project_members` row too (`ProjectService.create`
inserts it), so the membership list alone is the complete scope.

```sql
SELECT m.project_id FROM project_members m WHERE m.user_id = :userId;
```

### 2. Total matching tasks

`JpaTaskSearchQueries.count(projectIds, search)` — verbatim native SQL. `tasks` has no
`project_id`, so the scope goes through the column (same join as project.md query 9).

```sql
SELECT COUNT(*)
FROM tasks t
JOIN kanban_columns c ON c.id = t.column_id
WHERE c.project_id IN (:projectIds)
  AND t.search_vector @@ websearch_to_tsquery('simple', :search);

-- :projectIds = ('UrzWUH3e', 'Ab3dEf9h')   :search = 'login page'
```

### 3. Ranked page of hits + headline

`JpaTaskSearchQueries.search(projectIds, search, limit, offset)` — verbatim native SQL. The inner
query ranks and pages; `ts_headline` runs in the outer query so it is computed for the page's rows
only, not for every match. `:search` is bound once and used three times.

```sql
SELECT hit.id, hit.project_id,
       ts_headline('simple', concat_ws(' ', hit.title, hit.description),
                   websearch_to_tsquery('simple', :search), :headlineOptions) AS snippet
FROM (SELECT t.id, t.title, t.description, c.project_id, t.updated_at,
             ts_rank_cd(t.search_vector, websearch_to_tsquery('simple', :search)) AS score
      FROM tasks t
      JOIN kanban_columns c ON c.id = t.column_id
      WHERE c.project_id IN (:projectIds)
        AND t.search_vector @@ websearch_to_tsquery('simple', :search)
      ORDER BY score DESC, t.updated_at DESC, t.id
      LIMIT :limit OFFSET :offset) hit
ORDER BY hit.score DESC, hit.updated_at DESC, hit.id;

-- :limit = 20   :offset = 0   (offset = (page - 1) * limit, computed as a long — page has no upper
--                              bound; when offset >= total from query 2 this query is skipped)
-- :headlineOptions = 'StartSel=<U+E000>, StopSel=<U+E001>, MaxFragments=2, MaxWords=12, MinWords=6'
```

- **Ordering** (decision, JAV-34): pure relevance — `ts_rank_cd` (title hits outrank description
  hits), ties broken by `updated_at DESC`, then `id` so pagination is stable. No recency blending.
- **Input**: `websearch_to_tsquery` tolerates any syntax and any characters, but a single token of
  2047+ bytes raises `word is too long in tsquery` — hence the 200-character cap on `q` (and on the
  board's `search`) before anything is bound. `login page` →
  `'login' & 'page'`; `"login page"` → phrase; `login -page` → `'login' & !'page'`; `a or b` →
  `'a' | 'b'`; punctuation-only input → an empty query that matches nothing (PG emits a NOTICE, no
  error). A negation-only query (`-bug`) matches every task without that word at score 0.
- **Snippet**: `ts_headline` wraps matched words in the private-use characters U+E000 / U+E001.
  `SearchService` HTML-escapes the whole headline (`&`, `<`, `>`, `"`, `'`) and only then turns the
  markers into `<mark>` / `</mark>`, so `snippet` is an HTML fragment whose only tags are `<mark>`
  — safe to render directly. `ts_headline` also drops HTML-like tag tokens (`<b>`) from the source
  text itself.

### 4. Tasks by id, re-scoped to the caller's current memberships

`TaskRepository.findByIdInForUserWithAssigneesAndLabels(ids, userId)` — JPQL; relations assignees
and labels load via `@EntityGraph`. The load is **authorization-scoped again**, against the task's
*current* column and the caller's *current* memberships (not the id list captured for query 1): a
task moved out of the caller's projects, or a membership revoked, between query 3 and this
statement comes back missing and is skipped. `SearchService` re-orders the rows to the hit order of
query 3 and serializes each task with `Task.toJson({assignees, labels})` plus `project_id` (from
query 3 — for a task moved between two *authorized* projects in that window it names the project
the hit was found in) and `snippet`.

```sql
SELECT t.*
FROM tasks t
WHERE t.id IN (:ids)
  AND t.column_id IN (SELECT c.id
                      FROM kanban_columns c
                      WHERE c.project_id IN (SELECT m.project_id
                                             FROM project_members m
                                             WHERE m.user_id = :userId));

-- :ids = ('22222222-2222-4222-8222-222222222222', '33333333-3333-4333-8333-333333333333')
-- :userId = '11111111-1111-4111-8111-111111111111'
```

## Behaviour notes

- Tasks in archived columns and subtasks are searchable (as in `GET /tasks`; the board hides
  archived columns). A subtask hit carries `parent: { id }`.
- `q` (and the board's `search`) is capped at 200 characters by validation (400): a single token
  of ≥ 2047 bytes makes `websearch_to_tsquery` raise `word is too long in tsquery`.
- Fuzzy / typo-tolerant matching (`pg_trgm`) is deliberately **not** part of JAV-34 — it needs its
  own trigram index, predicate and threshold tuning and would be a second search implementation.
  Tracked in JAV-36.
- **Measured** (JAV-34 PR, 10,000 seeded tasks): the old `t.title ILIKE '%term%'` is a `Seq Scan`
  over every row on every request and can never use an index; the new predicate is a
  `Bitmap Index Scan on idx_tasks_search_vector` for both the board filter and the ranked page.
  Full `EXPLAIN (ANALYZE, BUFFERS)` output is in the PR description.
