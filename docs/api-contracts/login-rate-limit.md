# JAV-37 — Redis login rate limiting

## Purpose and request flow

Limit `POST /api/auth/login` per IP. Java handles HTTP; Redis owns the shared
counter and its expiry. A single backend could use an in-memory counter; Redis
is what lets several instances of the same backend see one quota.

1. Spring resolves the login handler; `RateLimitInterceptor` runs before body
   validation.
2. Take the IP Tomcat resolved, normalise IPv4/IPv6 (no DNS lookup) and build
   the key, for example `kanban:local:rate-limit:login:127.0.0.1`.
3. `RedisRateLimiter` executes `redis/rate-limit.lua` through Spring Data
   Redis/Lettuce.
4. The first request creates counter `1` with a 60 second TTL. Each further
   allowed request increments the counter without extending the TTL. Once 10 is
   reached, the script returns the remaining time and Java rejects the request.
5. The key expires; the next request starts a new window.

Lua performs the read, the check, the increment and the TTL write as one atomic
operation. Two backends cannot both spend the last unit of quota. A rejected
request does not increment the counter, does not extend the TTL and never calls
`AuthService.login`. If a key somehow loses its TTL, the script restores the TTL
while keeping the count.

This is not a sliding window: a client can send close to 10 requests at the end
of one window and 10 more at the start of the next. Several people behind the
same IP/NAT share one quota. This is not per-account lockout after N wrong
passwords.

## Enabling and configuration

Requires JDK 21+, Maven, Docker Compose, and the PostgreSQL/JWT setup from the
README.

```bash
docker compose -f compose.redis.yml up -d --wait
RATE_LIMIT_ENABLED=true mvn spring-boot:run
```

The Compose file binds Redis to `127.0.0.1` only; it is meant for local work.

| Variable | Default | Meaning |
| --- | --- | --- |
| `RATE_LIMIT_ENABLED` | `false` | Enable the limiter; when off, login needs no Redis |
| `RATE_LIMIT_MAX_REQUESTS` | `10` | Requests allowed per window |
| `RATE_LIMIT_WINDOW` | `60s` | Window length, minimum 1ms |
| `RATE_LIMIT_KEY_PREFIX` | `kanban:local:rate-limit` | Per-environment namespace |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis server |
| `REDIS_USERNAME` / `REDIS_PASSWORD` | empty | Redis authentication, if any |
| `REDIS_DATABASE` | `0` | Redis logical database |
| `REDIS_TIMEOUT` | `500ms` | Command timeout |
| `REDIS_CONNECT_TIMEOUT` | `500ms` | Connection timeout |
| `TRUSTED_PROXY_REGEX` | `(?!)` | Regex of trusted proxy IPs; trusts no proxy by default |

Backends that share a quota must use the same Redis database, prefix and quota
settings. Changing the prefix starts a fresh counter. Restarting a backend keeps
the quota, because it lives in Redis; Redis data loss, a Redis restart, or key
eviction can reset it. This Compose setup makes no promise that quota survives a
Redis failure; production deployments should weigh persistence/HA and the memory
policy.

## HTTP contract

Only login is limited. Malformed JSON, missing fields and wrong passwords all
count against the quota. OPTIONS/CORS preflight and every other endpoint do not.
Within quota, the existing responses are unchanged: 200 on success, 400 on failed
validation, 401 on invalid credentials.

Over quota:

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 42

{"message":"Too many login requests. Please try again later.","error":"Too Many Requests","statusCode":429}
```

`Retry-After` is the remaining seconds, rounded up (at least 1). CORS exposes
this header so the frontend can read it. When the limiter is on but Redis fails
or times out:

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{"message":"Login temporarily unavailable. Please try again later.","error":"Service Unavailable","statusCode":503}
```

503 carries no `Retry-After`, because there is no way to know when Redis will
recover. Login is not called, and the limit is not silently dropped. The server
logs the failure type only — never passwords, tokens, bodies or connection URLs.
Redis holds per-IP counters only, never credentials.

## Trying it with curl and watching the counter/TTL

Wait for any previous quota to expire, then send 11 requests quickly. Using `{}`
avoids needing a real account and avoids writing credentials anywhere:

```bash
for i in $(seq 1 11); do
  curl -s -o /dev/null -w '%{http_code}\n' \
    -X POST http://127.0.0.1:1996/api/auth/login \
    -H 'Content-Type: application/json' -d '{}'
done
```

Expected: ten 400s, then 429 on the eleventh. With a valid body, allowed requests
return 200/401 according to the authentication result. To see the headers, the
counter and the remaining time (milliseconds):

```bash
curl -i -X POST http://127.0.0.1:1996/api/auth/login \
  -H 'Content-Type: application/json' -d '{}'
docker compose -f compose.redis.yml exec redis redis-cli GET kanban:local:rate-limit:login:127.0.0.1
docker compose -f compose.redis.yml exec redis redis-cli PTTL kanban:local:rate-limit:login:127.0.0.1
```

The counter stops at 10. PTTL keeps counting down even while rejected requests
arrive. Wait out the `Retry-After` and send again to see a fresh quota. A
different IPv6 address or namespace produces a different key — note that
`localhost` may resolve to `::1`, which is a separate key from `127.0.0.1`.

## Trying it with two backends

Start Redis as above. Make sure PostgreSQL is migrated. Run the same code in two
terminals on separate port pairs, against the same Redis and namespace:

```bash
# Terminal A
RATE_LIMIT_ENABLED=true PORT=1996 SOCKET_IO_PORT=1997 DB_POOL_SIZE=2 mvn spring-boot:run
```

```bash
# Terminal B
RATE_LIMIT_ENABLED=true PORT=1998 SOCKET_IO_PORT=1999 DB_POOL_SIZE=2 mvn spring-boot:run
```

Wait for the old quota to expire, then send 6 requests to A and 4 to B inside the
same 60 seconds:

```bash
for i in $(seq 1 6); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:1996/api/auth/login \
    -H 'Content-Type: application/json' -d '{}'
done
for i in $(seq 1 4); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:1998/api/auth/login \
    -H 'Content-Type: application/json' -d '{}'
done
curl -i -X POST http://127.0.0.1:1998/api/auth/login -H 'Content-Type: application/json' -d '{}'
```

The last request returns 429; sending to A is rejected as well. Restarting B
while the window is still open stays rejected: the quota lives in Redis, not in
B's memory.

## Reverse proxies and spoofed IP headers

Tomcat uses native forwarding with the `TRUSTED_PROXY_REGEX` allowlist, which
defaults to `(?!)`. The interceptor reads `getRemoteAddr()` only; it never reads
`X-Forwarded-For` or `Forwarded` itself. A client sending forged headers directly
cannot change its quota.

Behind a reverse proxy, configure the regex to match that proxy's IP only. For a
local experiment:

```bash
TRUSTED_PROXY_REGEX='127\.0\.0\.1' RATE_LIMIT_ENABLED=true mvn spring-boot:run
```

Use that example locally only: it trusts every client coming from loopback. In
production, allowlist the exact proxy IPs and keep the backend from being reached
directly; never use `.*`. Tomcat reads X-Forwarded-For right to left, skipping
trusted proxies and taking the nearest untrusted IP. The proxy must write/append
the real peer IP to the header. Do not switch to
`server.forward-headers-strategy=framework` to bypass this allowlist.

## Tests and Redis failure

```bash
mvn test
docker compose -f compose.redis.yml up -d --wait
mvn -Predis-it verify
# Redis on another port:
mvn -Predis-it verify -Dredis.it.host=127.0.0.1 -Dredis.it.port=6380
```

The integration tests use a local Redis with no authentication, database 0, and
their own UUID prefix; they delete only their own keys and never run FLUSHDB. A
missing Redis fails the profile rather than skipping it. The normal test run needs
neither Redis nor PostgreSQL.

The tests cover: 100 concurrent requests admitting exactly 10; TTL/reset; IP
isolation; two HTTP servers sharing a quota across a restart; spoofed IPs; trusted
proxies; an unresponsive Redis returning 503 within the timeout; and disabled mode
still logging in. The HTTP tests use a real Tomcat with a mocked auth service and
do not import `.env`, so they need no database and no real account.

To see the failure path locally, stop Redis while the app has the limiter on:

```bash
docker compose -f compose.redis.yml stop redis
curl -i -X POST http://127.0.0.1:1996/api/auth/login -H 'Content-Type: application/json' -d '{}'
docker compose -f compose.redis.yml start redis
```

While it is stopped, login returns 503. Once Redis is up and the client
reconnects, login works again. Only stop the local Redis used for this exercise.

## Reference material

- [Spring Data Redis scripting](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)
- [Redis rate limiting](https://redis.io/docs/latest/develop/use-cases/rate-limiter/)
- [Redis expiration](https://redis.io/docs/latest/commands/expire/)
