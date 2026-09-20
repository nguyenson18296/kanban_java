# Auth — PostgreSQL queries on the login path

JAV-37 adds an optional Redis gate to `POST /api/auth/login`; it adds no PostgreSQL
queries or migrations. The queries below describe the existing login path, expressed
as equivalent SQL (Hibernate's aliases/column order and generated-value retrieval may vary).

## 1. Look up the user, including the password hash

Endpoint: `POST /api/auth/login`, after Redis allows the request and the body validates.
`AuthService.login` → `UserService.findOneByEmailWithPassword` → `UserRepository.findByEmail`.

```sql
SELECT u.*
FROM users AS u
WHERE u.email = :email;
```

Parameter: `:email`, e.g. `learner@example.com`. This loads the entity including
`password_hash`; the hash must never appear in API responses or logs. BCrypt comparison
and active-account checks happen in Java. Missing user/wrong password/inactive account
stop here without storing a refresh token.

## 2. Store the refresh token on successful login

Endpoint: `POST /api/auth/login`, only after successful authentication.
`AuthService.storeRefreshToken` → `RefreshTokenRepository.save` on a new identity entity.

```sql
INSERT INTO refresh_tokens
  (user_id, token_hash, device_info, ip_address, is_revoked, expires_at, created_at)
VALUES
  (:user_id, :token_hash, :device_info, :ip_address, false, :expires_at, CURRENT_TIMESTAMP)
RETURNING id, created_at;
```

- `:user_id`: authenticated user's UUID.
- `:token_hash`: SHA-256 of the randomly generated refresh token; never the raw token.
- `:device_info`: User-Agent, or NULL if absent/empty.
- `:ip_address`: container-resolved client address, or NULL if absent/empty.
- `:expires_at`: application-calculated token expiry (default now + 30 days).

JWT signing does not query PostgreSQL. Other auth paths and shared user queries are
unchanged by JAV-37; see `docs/queries/user.md` for user-module queries.

## Redis gate and skipped database work

`RateLimitInterceptor` calls `RedisRateLimiter` before the controller arguments are
resolved. Spring Data Redis uses EVALSHA with EVAL fallback for the Lua script in
`src/main/resources/redis/rate-limit.lua`. The script uses GET, SET PX, PTTL, INCR
and PEXPIRE on one namespaced key. It returns 0 for allow, or milliseconds until
expiry for deny; no user data or credentials are stored in Redis.

429 (quota exhausted), 503 (Redis unavailable), and 400 (body validation) all stop
before both SQL operations above. Disabled mode bypasses Redis and retains the
existing login database flow. Operational examples and HTTP bodies are documented
in `docs/api-contracts/login-rate-limit.md`.
