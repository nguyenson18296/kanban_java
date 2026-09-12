# Socket.IO gateway — raw PostgreSQL queries

Queries executed by `modules/events` (`EventsGateway`, `WsJwtGuard`, `NettySocketIoServer`).
The gateway owns no table: every query it triggers belongs to another module and is
cross-referenced here as `user#N` ([user.md](user.md)) or `project#N` ([project.md](project.md)).
The Socket.IO server runs on its own port (`SOCKET_IO_PORT`, default 1997).

**How to read this file**

- Example values: project id `'UrzWUH3e'` (8 alphanumeric chars), user id
  `'11111111-1111-4111-8111-111111111111'` (uuid).
- Rooms: `user:<userId>` (joined on connect and on `token:refresh`) and `project:<projectId>`
  (joined by the presence auto-join on connect, and explicitly via `board:join` since RBAC
  Task 13 / JAV-22). Room membership lives in netty-socketio memory — no table.
- Wire payloads here are camelCase (`{ "projectId": "…" }`), the events module's established
  style (see CLAUDE.md "Wire Format").

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | User by id (= `user#2`) | `WsJwtGuard.validateToken` → `AuthService.validateUserById` → `UserRepository.findById` | CONNECT with `auth.token`, `token:refresh` |
| 2 | Membership lookup by PK (= `project#1`) | `EventsGateway.handleBoardJoin` → `ProjectAccessService.ensureRole` → `ProjectMemberRepository.findByProjectIdAndUserId` | `board:join` |
| 3 | Project ids of a user (= `project#3`) | `PresenceService.handleConnectionOpened` → `ProjectMemberRepository.findProjectIdsByUserId` | first socket of a user after a successful CONNECT (async, presence module) |

## Queries

### 1. User by id (`user#2`)

`WsJwtGuard.validateToken(client)` reads the token from `handshake.auth.token` (or the
`Authorization` header), verifies it (`JwtService.verify`, no DB) and reloads the live user through
`AuthService.validateUserById` → `UserService.findOneById`. A missing or inactive user fails the
handshake (`connection:error` + disconnect) or the refresh (`token:refresh:error` + disconnect).

```sql
-- = user.md #2
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE id = :id;

-- :id = '11111111-1111-4111-8111-111111111111'  (the JWT `sub`)
```

### 2. Membership lookup by primary key (`project#1`)

`EventsGateway.handleBoardJoin(client, { projectId })` runs
`ProjectAccessService.ensureRole(projectId, userId, VIEWER)` **before** `client.join("project:" + projectId)`
— never join-then-verify. The gate's masked 404 (non-member or unknown project) and 403 both
surface as the same `board:join:error { projectId, message: "You do not have access to this project" }`,
so the socket channel is not a membership oracle; an unexpected failure (e.g. DB down) is logged at
error level and gets the same generic reply. A payload without a non-blank string `projectId`
(missing, other type, blank — echoed back as `null`), or a socket with no authenticated user, is
rejected without running the query. `board:leave` runs no query.

```sql
-- = project.md #1
SELECT project_id, user_id, role, joined_at
FROM project_members
WHERE project_id = :projectId
  AND user_id    = :userId;

-- :projectId = 'UrzWUH3e', :userId = '11111111-1111-4111-8111-111111111111'
```

### 3. Project ids of a user (`project#3`)

Not run by the gateway itself: `handleConnection` emits `WsConnectionOpenedEvent`, and the
`@Async` presence listener resolves the user's projects once per user (first socket) and joins the
socket to every `project:<id>` room via `SocketServer.joinRooms`. Listed because it is what puts a
socket into project rooms without a `board:join`.

```sql
-- = project.md #3
SELECT project_id
FROM project_members
WHERE user_id = :userId;
```

## Message query sequences

| Message | Queries, in order |
|---|---|
| CONNECT (`auth.token`) | **#1** (`user#2`) → `connection:established`; then async **#3** (`project#3`, presence auto-join + `presence:update`) |
| `token:refresh` `{ token }` | **#1** (`user#2`) → re-join `user:<id>` → `token:refresh:success` |
| `board:join` `{ projectId }` | **#2** (`project#1`, `viewer`) → join `project:<id>` → `board:join:success`; any failure → `board:join:error` (generic) |
| `board:leave` `{ projectId }` | none → leave `project:<id>` → `board:leave:success` |
| `emitToUser` / `emitToProject` (server → room) | none (in-memory room broadcast) |

Known gap (not addressed by JAV-22): a socket that is already in `project:<id>` stays there when
the user is removed from the project until it disconnects — neither the presence auto-join nor
`board:join` is re-evaluated on membership changes.
