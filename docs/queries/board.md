# Board — raw PostgreSQL queries

Queries executed by `modules/board` for `GET /board/{projectId}` (`BoardService.getBoard`,
`BoardQueries` / `JpaBoardQueries`). The route is gated by `@RequireProjectRole(VIEWER)`: before
anything below runs, `ProjectRoleInterceptor` loads the caller's membership ([project.md](project.md)
query 1) — non-members get the masked project 404, members of any role pass.

The optional `search` filter is **full-text search** since JAV-34: it matches whole words in the
task title **and** description through the generated `tasks.search_vector` column and its GIN
index (DDL and design notes in [search.md](search.md)). Both the board filter and
`GET /search/tasks` build their predicate from `TaskSearchSql` so they always parse input with
the same `simple` text-search configuration. Before JAV-34 the filter was
`t.title ILIKE '%term%'` — title only, substring matching, and a sequential scan on every request.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (aliases and column lists simplified so it
  pastes into `psql`). Queries 3 and 4 are verbatim native SQL; their `WHERE` is assembled from
  the filters that are present.
- Parameters are `:name`. Example values: project id `'UrzWUH3e'`, column ids `(1, 2, 3)`,
  `search = 'login page'`.
- `BoardService` maps a blank `search` (empty or whitespace) to *no filter*; `BoardQueryDto`
  rejects `search` longer than 200 characters with a 400 (a single 2047-byte token would make
  `websearch_to_tsquery` raise).

## Gate per route

| Route | Gate before the queries below |
|---|---|
| `GET /board/{projectId}` | `@RequireProjectRole(VIEWER)` → project.md query 1 (caller's membership) |

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Project exists? | `ProjectRepository.existsById` | every request (404 `Project with id "…" not found` when false) |
| 2 | Active columns of the project | `KanbanColumnRepository.findByIsArchivedFalseAndProjectIdOrderByPositionAsc` | every request; `[]` short-circuits to an empty board |
| 3 | Task count per column (filtered) | `JpaBoardQueries.countPerColumn` | every request with ≥ 1 column |
| 4 | Top-N task ids per column (filtered) | `JpaBoardQueries.topTasksPerColumn` (phase 1) | every request with ≥ 1 column |
| 5 | Tasks by id with assignees and labels | `JpaBoardQueries.topTasksPerColumn` (phase 2, JPQL) | when query 4 returned ids |

## Queries

### 1. Project exists?

```sql
SELECT count(*) > 0 FROM projects p WHERE p.id = :projectId;
```

### 2. Active columns of the project

```sql
SELECT c.*
FROM kanban_columns c
WHERE c.is_archived = false AND c.project_id = :projectId
ORDER BY c.position ASC;
```

### 3. Task count per column (filtered)

`JpaBoardQueries.countPerColumn(filters)` — verbatim native SQL. The `WHERE` always contains the
column clause; each other clause is appended only when its filter is present (`AND`-joined).

```sql
SELECT t.column_id AS column_id, COUNT(*) AS task_count
FROM tasks t
WHERE t.column_id IN (:columnIds)
  AND t.priority = CAST(:priority AS tasks_priority_enum)                                        -- ?priority
  AND t.search_vector @@ websearch_to_tsquery('simple', :search)                                  -- ?search (JAV-34)
  AND t.id IN (SELECT task_id FROM task_assignees WHERE user_id = CAST(:assigneeId AS uuid))       -- ?assigneeId
  AND t.id IN (SELECT task_id FROM task_labels WHERE label_id = :labelId)                          -- ?labelId
GROUP BY t.column_id;

-- :columnIds = (1, 2, 3)   :priority = 'high'   :search = 'login page'
-- :assigneeId = '22222222-2222-4222-8222-222222222222'   :labelId = 7
```

`websearch_to_tsquery` turns the raw user text into a tsquery and tolerates any syntax or
characters (`login page` → `'login' & 'page'`; `"login page"` → phrase; `-bug` → `!'bug'`;
punctuation-only input → an empty query that matches nothing); only a single token of 2047+ bytes
raises, which the 200-character cap on `search` rules out. The `@@` clause is served by
`idx_tasks_search_vector` (GIN); the column clause by `idx_tasks_column_id`.

### 4. Top-N task ids per column (filtered)

`JpaBoardQueries.topTasksPerColumn(filters, tasksPerColumn)` phase 1 — verbatim native SQL with
the same `WHERE` as query 3. Returns ids only; `[]` skips query 5.

```sql
SELECT ranked.id
FROM (SELECT t.id, ROW_NUMBER() OVER (PARTITION BY t.column_id ORDER BY t.position ASC) AS rn
      FROM tasks t
      WHERE t.column_id IN (:columnIds)
        AND t.search_vector @@ websearch_to_tsquery('simple', :search)   -- …plus the other optional clauses of query 3
     ) ranked
WHERE ranked.rn <= :tasksPerColumn;

-- :tasksPerColumn = 50
```

### 5. Tasks by id with assignees and labels

Phase 2 of `topTasksPerColumn` — JPQL `select distinct t from Task t left join fetch t.assignees
left join fetch t.labels where t.id in :ids order by t.columnId asc, t.position asc`.
`BoardService` then shapes the rows into `BoardResponse` records (no `Task.toJson`).

```sql
SELECT DISTINCT t.*, u.*, l.*
FROM tasks t
LEFT JOIN task_assignees ta ON ta.task_id = t.id
LEFT JOIN users u          ON u.id = ta.user_id
LEFT JOIN task_labels tl   ON tl.task_id = t.id
LEFT JOIN labels l         ON l.id = tl.label_id
WHERE t.id IN (:ids)
ORDER BY t.column_id ASC, t.position ASC;

-- :ids = ('22222222-2222-4222-8222-222222222222', '33333333-3333-4333-8333-333333333333')
```
