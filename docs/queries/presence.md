# Presence — Redis keys, Lua scripts and raw PostgreSQL queries

Online/offline presence shared by every backend instance (JSP-42). `PresenceService` joins sockets
to their project rooms and hands every connect/disconnect to `PresenceTracker`, which keeps this
backend's sockets in Redis through `RedisPresenceStore`; `PresenceBroadcaster` sends
`presence:update`. Frontend contract: [socket-events.md](../api-contracts/socket-events.md) §4.

**Redis is required for presence** (no in-memory fallback). It uses the `REDIS_*` connection of
rate limiting and the dashboard cache, under its own prefix `PRESENCE_KEY_PREFIX` (default
`kanban:local:presence`): the **same** prefix on every instance of one environment, a
**different** one per environment.

## Gate per route

| Route | Gate before the reads below |
|---|---|
| `GET /presence?userIds=…` (max 100), `GET /presence/me` | `@JwtAuth` → [user.md](user.md) query 2. No project gate: any signed-in user can read any user's presence, as in Nest. |

## PostgreSQL

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Project ids of a user (= [project.md](project.md) #3) | `PresenceService.handleConnectionOpened`, `PresenceBroadcaster.send` → `ProjectMemberRepository.findProjectIdsByUserId` | a user's first socket on this backend (room join); every online/offline transition (rooms of one `presence:update`) |

```sql
SELECT project_id
FROM project_members
WHERE user_id = :userId;

-- :userId = '719cacaf-69fd-423a-803d-eb7f813ccb02'
```

The presence reads (`GET /presence*`) run no PostgreSQL beyond the auth reload; they are one Redis
script call.

## Redis keys

`P` = `PRESENCE_KEY_PREFIX`. Sample values from a two-backend run (alice has one tab per backend):

| Key | Type | Content | TTL |
|---|---|---|---|
| `P:user:<userId>:sockets` | sorted set | member = socket id, **score = expiresAt** (epoch ms, Redis clock) | none; removed by Redis when the last member goes |
| `P:user:<userId>:status` | hash | `online` = `1`/`0`, `changed_at` = epoch ms of the last online↔offline transition | none (see retention) |
| `P:expiry` | sorted set | member = user id, score = that user's **earliest** socket expiry | none |

```
> ZRANGE kanban:demo:presence:user:<alice>:sockets 0 -1 WITHSCORES
d67eff41-550d-4494-a79e-f22b5c121f2a 1791946922856      ← tab on backend B
46693669-5e57-4872-9c05-3c38a2d38695 1791946923207      ← tab on backend A
> HGETALL kanban:demo:presence:user:<alice>:status
online 1 changed_at 1791946899931
> ZRANGE kanban:demo:presence:expiry 0 -1 WITHSCORES
<alice> 1791946922856 <carol> 1791946922856 <bob> 1791946923207
```

- A socket is **live** while `score > now`. Reads count only live sockets (`ZCOUNT key (now +inf`),
  so an expired socket stops counting at once, even before it is cleaned up.
- **One clock:** every script takes `now` from `redis.call('TIME')`, so clock differences between
  backends do not matter.
- **Retention:** a `status` hash stays after the user goes offline. It holds `lastChangedAt` and the
  flag that makes transitions happen once. That is one small hash per user who ever connected.
  Deleting the hash of an offline user is safe; their `lastChangedAt` becomes `null`.

## Lua scripts (`src/main/resources/redis/`)

Each script is one atomic step: nothing else runs on Redis between its read and its write. That is
what makes the online and offline transitions happen **exactly once** across all backends.

| Script | KEYS | ARGV | Returns | Called by |
|---|---|---|---|---|
| `presence-register.lua` | `P:expiry`, then per user `sockets`, `status` | TTL ms, then per user: id, socket count, socket ids | `[userId, liveCount, changedAt]` per user that **went online** | connect, heartbeat |
| `presence-unregister.lua` | same | per user: id, socket count, socket ids (count `0` = only purge expired sockets) | `[userId, 0, changedAt]` per user that **went offline** | disconnect, prune, reconcile, shutdown |
| `presence-due.lua` | `P:expiry` | max ids | user ids whose earliest socket has expired | reconcile |
| `presence-read.lua` | per user `sockets`, `status` | — | per user `liveCount`, `changed_at` (`0` if never) | `GET /presence*` |

- **register:** `ZADD` each socket with `now + TTL`, an upsert, so the heartbeat also restores sockets
  after Redis lost them. Then it purges expired members (`ZREMRANGEBYSCORE -inf now`) and stores the
  earliest expiry in `P:expiry`. If `status.online` is not `1`, it sets `online=1, changed_at=now` and
  reports the transition.
- **unregister:** `ZREM` the given sockets, then purge expired ones. If none is left, it removes the
  user from `P:expiry`, and if `status.online` is `1` it sets `online=0, changed_at=now` and reports
  the transition. Otherwise it updates the earliest expiry.
- Calls are split into batches of ~200 sockets, and one user's sockets always stay in one batch.

What one register looks like in `MONITOR` (a user's first socket, so it also records the transition):

```
[0 127.0.0.1:32904] "EVALSHA" "<sha>" "3" "P:expiry" "P:user:u1:sockets" "P:user:u1:status" "30000" "u1" "1" "tab-1"
[0 lua] "TIME"
[0 lua] "ZADD" "P:user:u1:sockets" "1791947458008" "tab-1"
[0 lua] "ZREMRANGEBYSCORE" "P:user:u1:sockets" "-inf" "1791947428008"
[0 lua] "ZRANGE" "P:user:u1:sockets" "0" "0" "WITHSCORES"
[0 lua] "ZADD" "P:expiry" "1791947458008" "u1"
[0 lua] "HGET" "P:user:u1:status" "online"
[0 lua] "HSET" "P:user:u1:status" "online" "1" "changed_at" "1791947428008"
[0 lua] "ZCARD" "P:user:u1:sockets"
```

A heartbeat stops after the `HGET` (the user is already online).

## Flows

```
connect    gateway → WsConnectionOpenedEvent (@Async) → PresenceService: join project rooms (query 1, once
           per user per backend) → PresenceTracker.connected → [worker] socket still connected?
           → registry → register → online transition? → PresenceBroadcaster (query 1) → presence:update
disconnect gateway → WsConnectionClosedEvent → PresenceTracker.disconnected → [worker] registry.remove
           → unregister (under the user it was registered with) → offline transition? → broadcast
tick       [worker, every heartbeat interval]
           1. prune: sockets in the registry that Socket.IO no longer knows → unregister
           2. heartbeat: register every remaining socket (also a probe when there are none)
           3. reconcile (after a TTL of healthy ticks): due → unregister(user, no sockets) → broadcast
```

One worker thread (`presence-worker`) runs all of a backend's Redis writes, so a heartbeat can never
re-register a socket whose disconnect has just been handled. Broadcasts run on their own thread
(`presence-broadcast`) so the heartbeat never waits on PostgreSQL. `presence:update.timestamp` is
the `changed_at` the script wrote, so it always equals `lastChangedAt`.

## Timings

| Setting | Default | Env |
|---|---|---|
| Heartbeat interval | 10s | `PRESENCE_HEARTBEAT_INTERVAL` |
| Socket TTL (must be ≥ 3 × interval, checked at startup) | 30s | `PRESENCE_CONNECTION_TTL` |
| Retry while Redis is unavailable | 1s (or the interval, if shorter) | — |
| Reconcile starts after | ⌈TTL / interval⌉ + 1 healthy heartbeats in a row (4 → ≥ 30s) | — |
| Socket.IO ping interval / timeout | 25s / 20s | `SOCKET_IO_PING_INTERVAL` / `SOCKET_IO_PING_TIMEOUT` |

Maximum delay before a user shows offline (defaults):

| Situation | Reads say offline | `presence:update` sent |
|---|---|---|
| Last tab closed | immediately | immediately, by the user's backend |
| Client loses its network | ≤ 45s (ping 25s + timeout 20s → disconnect) | same |
| Backend killed (`kill -9`, crash) | ≤ TTL = 30s after its last heartbeat | ≤ TTL + interval ≈ 40s, by a peer that reconciles |
| Backend stopped normally | immediately (it unregisters its sockets) | none: its clients reconnect elsewhere |

The last heartbeat happens at most one interval before a crash, so in the worst case reads flip
after ~30s and the event follows ≤ 10s later. Measured with interval 2s / TTL 6s: reads flipped ~5s
after `kill -9` and the peer's event came ~7s after it.

## Failure behavior

- **Redis unavailable.** `GET /presence*` → 503 `Presence temporarily unavailable. Please try again
  later.` (the log has the exception class only). The tracker logs
  `Presence paused: Redis unavailable, …` once (on its next heartbeat, ≤ 10s), stops writing, keeps
  its socket list and retries every second. It never broadcasts offline because of a Redis error.
  Lettuce reconnects at least once a second (`RedisClientConfig`), so within ~1s of Redis answering
  again `Presence resumed: …` is logged and every socket is re-registered. Measured after a 35s
  outage: Redis up at 12.281 → reconnected 12.525 → re-registered 12.526 → count back 12.568, and no
  offline event. The 503 came back in 0.5s (`REDIS_TIMEOUT`). Reconcile waits
  for a full TTL of healthy ticks, so peers recovering from the same outage re-register before
  anything is purged. Sockets that disconnected during the outage are not unregistered; they expire
  and are reconciled. Each failed call also logs `Presence Redis <op> failed (<class>)`.
- **Redis lost its data** (restart without persistence, `FLUSHDB`): the next heartbeat of each
  backend writes the sockets back. `status` is gone too, so users come back with a new online
  transition and a new `changed_at`.
- **Backend killed:** its sockets stop being refreshed and expire; any peer's reconcile purges them
  and sends `presence:update` for users who have nothing left.
- **Backend cut off from Redis while others still reach it:** this looks like a crash. Peers
  reconcile its sockets after the TTL (offline events). When it reaches Redis again, its heartbeat
  registers them back (online events).
- **Lost events (known limits):** `presence:update` reaches only the clients of the backend that
  saw the change (no cross-backend fan-out; out of scope). If a script ran but the client timed out
  waiting for its reply, the state is right but that event is lost. Clients refetch on connect,
  focus and an interval, and drop events older than the state they hold.
- **Undercount after a long outage:** for up to ~1s after Redis comes back, until every backend has
  sent its first heartbeat.

## Design notes

- **Why a sorted set and not one key per socket with `EXPIRE`:** a TTL belongs to a whole key, never
  to a set member (hands-on step 1). One key per socket with its own TTL would expire on time, but
  nobody would learn about it. Learning about it would need keyspace notifications, which are
  fire-and-forget (lost while no backend is subscribed), off by default (`notify-keyspace-events`)
  and fired when Redis gets round to evicting the key, not at the TTL. With the expiry as a score,
  the expiry is data: reads ignore expired members at once, and `P:expiry` lets a reconcile find
  them with one range query and no scan.
- **Why Lua and not `MULTI`/`WATCH`:** the transition depends on what the script reads (is anyone
  left? was the user online?). A script does read-decide-write in one step; `WATCH` would have to
  retry under contention.
- **Heartbeat vs `lastChangedAt`:** the heartbeat moves only the scores. `changed_at` is written only
  on a transition, so `lastChangedAt` means "online/offline since", not "last seen".
- **Why stop in `SmartLifecycle` phase 1:** `LettuceConnectionFactory` closes its connection in
  phase 0, before any `@PreDestroy` runs. The tracker stops first, so its final unregister still
  reaches Redis (`PresenceHttpIT` covers this).
- **Why cap Lettuce's reconnect delay at 1s (`RedisClientConfig`):** Lettuce's default backoff grows
  to 30s. After a long outage a backend could stay paused for up to 30s, and backends would come back
  up to 30s apart. The reconcile wait (one TTL) would then no longer cover the gap, and a backend
  that recovered early could reconcile the sockets of one that had not reconnected yet. Measured
  before the cap: 28s from Redis up to presence resumed.
- **Requirements:** standalone or Sentinel Redis (multi-key scripts; no Cluster); `maxmemory-policy`
  `noeviction` or `volatile-*`, because presence keys have no TTL; Lettuce on its default single
  shared connection, with no pool, so a command that timed out can never be applied after a later
  one.

## Hands-on (redis-cli)

Start Redis: `docker compose -f compose.redis.yml up -d --wait`. In zsh, write `${P}:user`, not
`$P:user`: zsh reads `$P:u` as "uppercase P".

```bash
r() { docker compose -f compose.redis.yml exec -T redis redis-cli "$@"; }
P=kanban:demo:presence     # a throwaway prefix; the app uses kanban:local:presence
```

**1. Why not a plain Set + `EXPIRE`**

```bash
r SADD ${P}:set-demo tab-1 tab-2   # 2
r SCARD ${P}:set-demo              # 2
r SREM ${P}:set-demo tab-1; r SCARD ${P}:set-demo   # 1
r EXPIRE ${P}:set-demo 3
r SADD ${P}:set-demo tab-3; r TTL ${P}:set-demo     # still ≤ 3: adding did not extend it
sleep 4; r EXISTS ${P}:set-demo    # 0 — the whole set is gone, tab-3 included
```

**2. Expiry as a score, by hand**

```bash
NOW=$(( $(date +%s) * 1000 ))
r ZADD ${P}:user:u1:sockets $((NOW + 30000)) tab-1 $((NOW + 2000)) tab-2
r ZRANGE ${P}:user:u1:sockets 0 -1 WITHSCORES
sleep 3; NOW=$(( $(date +%s) * 1000 ))
r ZCOUNT ${P}:user:u1:sockets "($NOW" +inf        # 1 — tab-2 expired, tab-1 is live
r ZREMRANGEBYSCORE ${P}:user:u1:sockets -inf $NOW # 1 — what a reconcile purges
r DEL ${P}:user:u1:sockets
```

**3. The real scripts**

```bash
L=src/main/resources/redis
K=("${P}:expiry" "${P}:user:u1:sockets" "${P}:user:u1:status")
r EVAL "$(cat $L/presence-register.lua)" 3 "${K[@]}" 30000 u1 2 tab-1 tab-2   # u1 2 <now>: online
r HGETALL ${P}:user:u1:status                                                 # online 1 changed_at <now>
r EVAL "$(cat $L/presence-register.lua)" 3 "${K[@]}" 30000 u1 2 tab-1 tab-2   # (empty): heartbeat, changed_at unchanged
r EVAL "$(cat $L/presence-unregister.lua)" 3 "${K[@]}" u1 1 tab-1             # (empty): still online
r EVAL "$(cat $L/presence-unregister.lua)" 3 "${K[@]}" u1 1 tab-2             # u1 0 <now>: offline
r EVAL "$(cat $L/presence-read.lua)" 2 ${P}:user:u1:sockets ${P}:user:u1:status # 0 <changed_at>
r DEL ${P}:user:u1:status
```

**4. Watch the running app** (`mvn spring-boot:run`, frontend open in two tabs)

```bash
A=kanban:local:presence
r ZRANGE ${A}:user:<userId>:sockets 0 -1 WITHSCORES   # 2 sockets; scores move every 10s
r HGETALL ${A}:user:<userId>:status                   # changed_at stays put
r ZRANGE ${A}:expiry 0 -1 WITHSCORES
r MONITOR                                             # Ctrl+C to stop
curl -s localhost:1996/api/presence/me -H "Authorization: Bearer $TOKEN"
```

Close one tab: one socket is left and the user stays online. Close the last one: the sockets key
disappears, `online` becomes `0` and `changed_at` moves.

**5. Two backends and `kill -9`**

```bash
# Terminal 1 — backend A (1996/1997) — and terminal 2 — backend B (2996/2997); same prefix.
# Two backends double the DB connections: keep DB_POOL_SIZE ≤ 2 on the Supabase session pooler.
mvn -q -DskipTests package
DB_POOL_SIZE=2 java -jar target/kanban-backend-1.0.0.jar
DB_POOL_SIZE=2 PORT=2996 SOCKET_IO_PORT=2997 java -jar target/kanban-backend-1.0.0.jar
```

1. Open the frontend as usual (sockets on A), and a second frontend dev server with
   `VITE_WS_URL=http://localhost:2997` (sockets on B). Sign in as the same user in both.
2. `GET /api/presence/me` on `:1996` and on `:2996` gives the same `connectionCount: 2`.
3. `kill -9 <pid of A>`. Reads on B show 1 after ≤ 30s, and the user stays online thanks to the
   tab on B. A user whose only tab was on A goes offline, and B sends their `presence:update` within
   ~40s.
4. Stop B with Ctrl+C (a normal stop): its sockets disappear from Redis at once.

**6. Redis outage**

```bash
docker compose -f compose.redis.yml stop redis    # GET /api/presence → 503; log: "Presence paused …"
docker compose -f compose.redis.yml start redis   # log: "Presence resumed …"; counts back within ~1s
```

Nobody receives an offline event, however long the outage lasts.

**7. Clean up a demo prefix** (never `FLUSHDB` on a shared Redis)

```bash
r --scan --pattern "${P}:*" | xargs -r docker compose -f compose.redis.yml exec -T redis redis-cli DEL
```

## Tests

| Acceptance check | Test |
|---|---|
| 2 tabs → 2, close one → 1 and online, close the last → 0 and offline | `RedisPresenceStoreIT.twoTabsOnTwoBackends`, `PresenceHttpIT.twoBackendsShareState` |
| No duplicates or wrong counts under concurrency | `RedisPresenceStoreIT.concurrentTransitionsHappenOnce`, `idempotent` |
| A killed backend's sockets expire; online stays true while a socket on another backend lives | `PresenceTrackerIT.crashedBackendIsReconciled`, `RedisPresenceStoreIT.expiredSocketsAreReconciled` |
| Two backends read the same state through the API | `PresenceHttpIT.twoBackendsShareState` |
| Heartbeats never change `lastChangedAt` | `RedisPresenceStoreIT.heartbeatKeepsChangedAt` |
| Redis down → generic 503, no offline, recovery re-registers | `PresenceHttpIT.unavailableRedisIsGeneric503`, `PresenceTrackerIT.outageNeverReportsOffline`, `PresenceTrackerTest.survivesOutage`, `WebLayerTest.presenceRedisDown` |
| Data loss, normal shutdown | `PresenceTrackerIT.dataLossIsRepaired`, `gracefulShutdown`, `PresenceHttpIT.shutdownUnregistersBeforeRedisCloses` |

Run: `docker compose -f compose.redis.yml up -d --wait && mvn -Predis-it verify`.
