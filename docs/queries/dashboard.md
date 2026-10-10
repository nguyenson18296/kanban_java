# Dashboard — raw PostgreSQL queries and Redis cache

Queries and Redis commands behind `GET /projects/{projectId}/dashboard` (JSP-44:
`DashboardController` → `DashboardService` → `JpaDashboardQueries`, cached by `DashboardCache`).
Frontend contract: [project-dashboard.md](../api-contracts/project-dashboard.md).

The route is gated by `@RequireProjectRole(VIEWER)`: on **every** request, before Redis is read,
`JwtAuthInterceptor` reloads the caller ([user.md](user.md) query 2) and `ProjectRoleInterceptor`
loads their membership ([project.md](project.md) query 1). Non-members get the masked project 404,
so a cached snapshot is never served to someone who has left the project.

**How to read this file**

- Query 1 is verbatim native SQL (`JpaDashboardQueries.SQL`), formatted to paste into `psql`.
- Parameters are `:name`. Example values: project id `'UrzWUH3e'`, `asOf` `'2026-10-07T02:30:00.000Z'`.

## Gate per route

| Route | Gate before the queries below |
|---|---|
| `GET /projects/{projectId}/dashboard` | `@RequireProjectRole(VIEWER)` → user.md query 2 + project.md query 1, on cache hits too |

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Project statistics snapshot | `JpaDashboardQueries.snapshot` | cache miss, cache disabled, or Redis unavailable |

Statements actually sent per request, from the PostgreSQL statement log (2026-10-07):

| Request | Statements |
|---|---|
| Cache miss | user by id, membership, **query 1** |
| Cache hit | user by id, membership — no aggregate |

## Queries

### 1. Project statistics snapshot

One statement, so every number comes from the same MVCC snapshot without an explicit
transaction. `scoped` is the counted set: tasks **and subtasks** whose column belongs to the
project and is not archived (tasks have no `project_id`; the column decides). The status branch
never joins `task_assignees`, so a task with several assignees cannot inflate the totals; the
assignee branch counts one row per `(task_id, user_id)` — the table's primary key.

```sql
WITH scoped AS (
  SELECT t.id, t.status, t.due_date
  FROM tasks t
  JOIN kanban_columns c ON c.id = t.column_id
  WHERE c.project_id = :projectId AND c.is_archived = false
), status_counts AS (
  SELECT CAST(s.status AS text) AS k, COUNT(*) AS n,
    COUNT(*) FILTER (WHERE s.due_date < CAST(:asOf AS timestamptz)
      AND s.status NOT IN ('done', 'cancelled')) AS overdue,
    COUNT(*) FILTER (WHERE NOT EXISTS (
      SELECT 1 FROM task_assignees ta WHERE ta.task_id = s.id)) AS unassigned
  FROM scoped s GROUP BY s.status
), assignee_counts AS (
  SELECT CAST(ta.user_id AS text) AS k, COUNT(*) AS n
  FROM scoped s JOIN task_assignees ta ON ta.task_id = s.id
  GROUP BY ta.user_id
)
SELECT 'status' AS kind, k, n, overdue, unassigned FROM status_counts
UNION ALL
SELECT 'assignee', k, n, 0, 0 FROM assignee_counts;
-- :projectId = 'UrzWUH3e'
-- :asOf      = '2026-10-07T02:30:00.000Z'  (DashboardService: Instant.now() truncated to ms = computed_at)
```

- Rows: up to 5 `status` rows (statuses without tasks are absent; the service fills `0`) and one
  `assignee` row per assignee. An empty project returns no rows.
- `overdue`: a NULL `due_date` makes the comparison NULL, so it is never counted. `asOf` is bound
  as an `Instant` and cast to `timestamptz`; casting to `timestamp` would shift it by the JVM time
  zone (`DashboardQueriesIT` runs in Asia/Ho_Chi_Minh to catch that).
- `total_tasks`, `overdue_tasks` and `unassigned_tasks` are the sums over the status rows.

## Measurements (2026-10-07)

Dataset: [dashboard-bench-seed.sql](dashboard-bench-seed.sql) on the local `kanban_bench`
database (PostgreSQL 17, `compose.postgres.yml`) — 10 projects × 20 columns (2 archived), 200,000
tasks (40,000 subtasks), ~300,000 `task_assignees` rows; plus a 300-task project `BENCHSML`.
Laptop, warm buffers; numbers are for comparison, not a target.

| Project | Counted tasks | `EXPLAIN ANALYZE` execution | HTTP miss (median of 15) | HTTP hit (median of 15) |
|---|---|---|---|---|
| `BENCH001` | 18,000 | 73 ms (8.5 ms of it JIT), planning 0.9 ms | 41 ms | 4.1 ms |
| `BENCHSML` | 300 | 21 ms | 27 ms | 3.5 ms |

A hit costs two primary-key lookups (user, membership) plus one Redis `GET`.

Plans (`EXPLAIN (ANALYZE, BUFFERS)` of query 1):

- `BENCH001`: `scoped` is materialized once (it is referenced twice) — Seq Scan on `tasks`
  hash-joined to the project's 18 non-archived columns → 18,000 rows. The most expensive part is the
  `NOT EXISTS` sub-plan: 18,000 Index Only Scans on `task_assignees_pkey` (67,000 buffer hits;
  heap fetches until autovacuum sets the visibility map). The assignee branch hash-joins `scoped`
  with a Seq Scan of `task_assignees`.
- `BENCHSML`: the planner estimates `scoped` at 3,927 rows (actual 300 — it cannot know which
  columns the join keeps) and still picks Parallel Seq Scans of `tasks` and `task_assignees`:
  21 ms. With `SET enable_seqscan = off` the same query runs as nested loops over
  `kanban_columns_pkey`, `idx_tasks_column_id` and `task_assignees_pkey` in **1.4 ms**.

**Decision: no new index (no V7).** Every index the fast plan needs already exists
(`idx_tasks_column_id`, `task_assignees_pkey`); at ~34 MB of `tasks` a sequential scan is the
planner's cheaper estimate, and the slow case is the row *estimate*, not a missing index. Re-measure
when `tasks` grows about 10× or real latencies on Supabase disappoint; the fix to try then is
passing the project's column ids as literal values (`t.column_id IN (...)`, so per-value statistics
apply), not another index.

Reproduce:

```bash
docker compose -f compose.postgres.yml up -d --wait
docker compose -f compose.postgres.yml exec -T postgres psql -U kanban_it -d kanban_it -c 'CREATE DATABASE kanban_bench'
# start the app once against kanban_bench so Flyway creates the schema (it needs no .env):
POSTGRES_HOST=127.0.0.1 POSTGRES_PORT=55432 POSTGRES_DB=kanban_bench POSTGRES_USER=kanban_it \
  POSTGRES_PASSWORD=kanban_it POSTGRES_SSLMODE=disable JWT_SECRET=bench SOCKET_IO_ENABLED=false \
  DASHBOARD_CACHE_ENABLED=true mvn spring-boot:run
docker compose -f compose.postgres.yml exec -T postgres psql -U kanban_it -d kanban_bench < docs/queries/dashboard-bench-seed.sql
# then EXPLAIN (ANALYZE, BUFFERS) query 1 with :projectId = 'BENCH001'
```

## Redis cache

Cache-aside in `DashboardService.get`; `DashboardCache` owns every Redis call. Settings:
`DASHBOARD_CACHE_ENABLED` (default `false`), `DASHBOARD_CACHE_TTL` (default `60s`, at least `1s`),
`DASHBOARD_CACHE_KEY_PREFIX` (default `kanban:local:dashboard`); the connection and its 500 ms
timeouts are the shared `REDIS_*` settings.

| | |
|---|---|
| Key | `<prefix>:v1:project:<projectId>`, e.g. `kanban:local:dashboard:v1:project:UrzWUH3e`. `v1` is the payload version: bump it when `DashboardResponse` changes shape. |
| Value | The response JSON, `computed_at` included (a hit keeps the original time). |
| Read | `GET key`. Never `EXPIRE`/`GETEX`: reads never extend the TTL. |
| Write (miss only) | `SET key json PX <ttl>` — value and TTL in one command, so an entry can never exist without an expiry. |
| Evict | `DEL key [key …]` — one command for every project a write touched. |

Failures never fail a request:

| Situation | Behavior | Log |
|---|---|---|
| Redis down or timing out on `GET` | compute from PostgreSQL, **skip** the `SET` (no second timeout) | `WARN Dashboard cache read failed (<ExceptionClass>)` |
| Unreadable entry (bad JSON, missing field) | treated as a miss; the fresh `SET` overwrites it | `WARN … is unreadable; recomputing` |
| `SET` or `DEL` fails | ignored; a stale entry dies with its TTL | `WARN Dashboard cache write/eviction failed (…)` |
| Hit / miss / compute time | — | `DEBUG` on `com.kanban.modules.dashboard` |

### Invalidation

Writes evict right after the statement that commits them (the task and column services run no
outer transaction, so each save commits on its own). `evictAfterCommit` would wait for
`afterCommit` — and evict nothing on rollback — if a caller ever ran inside a transaction.

| Write | Projects evicted |
|---|---|
| `POST /tasks`, `POST /tasks/{id}/subtasks` | the target column's project |
| `PATCH /tasks/{id}` (any field) | the task's project; with `column_id` also the project it now belongs to ([project.md](project.md) query 9 after the save) |
| `DELETE /tasks/{id}` | the projects of the task and its subtasks, read **before** the delete ([task.md](task.md) query 10) — subtasks cascade and may sit in other projects |
| `POST`/`DELETE /tasks/{id}/assignees` | the task's project |
| `PATCH /tasks/{id}/move` | source and target projects (one key when equal) |
| `PATCH /columns/{id}` changing `project_id` | old and new project (the column's tasks moved with it) |

Not evicted, on purpose: label changes, reorders (counts unchanged); column create/delete (a new
column is empty and a column with tasks cannot be deleted — `RESTRICT`); project delete (also
`RESTRICT`; its memberships cascade, so the gate answers 404 first); membership changes (checked on
every request). There is no column archive endpoint yet — whoever adds one must evict the column's
project.

### Consistency contract v1

- A snapshot is internally consistent (one statement). The cache only ever receives numbers a
  reader computed from committed rows, so uncommitted or rolled-back data cannot be cached.
- Staleness is bounded by the TTL, never longer:
  1. a reader that computed before a concurrent write commits can `SET` its snapshot just after the
     writer's `DEL` (pinned by `DashboardCacheIT`);
  2. `overdue_tasks` does not change while time passes without writes;
  3. writes that bypass this service (the Nest app on the same database, `psql`).

## Hands-on with redis-cli

Run the app with `DASHBOARD_CACHE_ENABLED=true` (Redis from `compose.redis.yml`), then:

```bash
R='docker compose -f compose.redis.yml exec redis redis-cli'
KEY=kanban:local:dashboard:v1:project:UrzWUH3e
URL=http://localhost:1996/api/projects/UrzWUH3e/dashboard
AUTH="Authorization: Bearer $ACCESS_TOKEN"

# 1. Miss then hit: the first request writes the key, the second reads it (compare the times).
curl -s -o /dev/null -w '%{time_total}\n' -H "$AUTH" $URL
curl -s -o /dev/null -w '%{time_total}\n' -H "$AUTH" $URL
$R GET $KEY                     # the cached JSON
$R PTTL $KEY                    # ms left; run again after a few requests: it only goes down

# 2. Watch the commands: GET on each request, SET ... PX on a miss, DEL after a task write.
$R MONITOR                      # in a second terminal; Ctrl-C to stop
$R INFO stats | grep keyspace   # keyspace_hits / keyspace_misses (whole Redis instance)

# 3. Eviction: change a task through the API, the key disappears, the next GET recomputes.
curl -s -X PATCH -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"status":"done"}' http://localhost:1996/api/tasks/$TASK_ID > /dev/null
$R EXISTS $KEY                  # 0

# 4. Corrupt entry: still 200 with correct numbers, and the key is overwritten.
$R SET $KEY 'not json' PX 60000
curl -s -H "$AUTH" $URL
$R GET $KEY

# 5. Redis outage: the dashboard and task writes keep working (WARN in the app log).
docker compose -f compose.redis.yml stop redis
curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -H "$AUTH" $URL
docker compose -f compose.redis.yml start redis
```

Delete only the keys you created (`DEL`); never `FLUSHDB` a shared Redis.
