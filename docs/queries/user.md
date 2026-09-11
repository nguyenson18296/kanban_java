# Users — raw PostgreSQL queries

Queries executed by `modules/user` (`UserController` / `UserService` / `UserRepository`), plus the
other modules that read `users` through `UserRepository` (auth, task, project, mention,
invitation). Since RBAC Task 12 (JAV-21) every user route is `@JwtAuth`, and
`GET /users/{id}/projects` is **self-only** (403 for any other id). The full user list stays
visible to any authenticated user — it powers the invite/mention pickers — an accepted exposure.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (column lists simplified so it pastes into `psql`).
- Parameters are `:name`. Example values: user id `'11111111-1111-4111-8111-111111111111'` (uuid),
  email `'alice@example.com'`.
- Table: `users (id UUID PK DEFAULT uuid_generate_v4(), email VARCHAR(255) UNIQUE ("UQ_users_email"),
  full_name VARCHAR(150), password_hash TEXT, role users_role_enum, avatar_url TEXT, is_active BOOLEAN,
  created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)`, indexes `idx_users_role`, `idx_users_is_active`.
- `password_hash` is a mapped column, so every `SELECT` below fetches it; it is never serialized
  (`User.toJson()` omits it) and only `POST /auth/login` is allowed to read it
  (`UserService.findOneByEmailWithPassword`).
- **Every `@JwtAuth` route in the API starts with #2**: `JwtAuthInterceptor` verifies the bearer
  token, then reloads the live user (`AuthService.validateUserById` → `UserService.findOneById`) and
  answers `401 Unauthorized` for a missing or inactive account. Other module docs reference it as
  `user#2` instead of repeating it.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | All users | `UserRepository.findAll` | `GET /users` |
| 2 | User by id | `UserRepository.findById` | every 🔒 route (JWT reload); `GET /users/{id}`; `GET /users/me/projects` + `GET /users/{id}/projects` (existence check); `POST /auth/refresh`; `POST /projects/{projectId}/invitations` (inviter for the notification) |
| 3 | User by email | `UserRepository.findByEmail` | `POST /auth/login`; `POST /auth/register` (duplicate pre-check); `POST /projects/{projectId}/invitations` (does the invitee have an account?) |
| 4 | Insert user | `UserRepository.save` (new) | `POST /auth/register` |
| 5 | Users by ids | `UserRepository.findByIdIn` | `TaskService.resolveUsers` (assignee ids on `POST /tasks`, `PATCH /tasks/{id}`, `POST /tasks/{id}/assignees`); `ProjectService.validateUsers` (`POST /projects/{id}/members`) |
| 6 | Active user ids among ids | `UserRepository.findActiveIdsByIdIn` | `MentionService.resolveMentionedUserIds` (`@[id]` mentions in comment create/update) |
| 7 | Active user ids by full name | `UserRepository.findActiveIdsByFullNameIn` | `MentionService.resolveMentionedUserIds` (`@Name` mentions) |
| 8 | Projects of a user | `ProjectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc` (= `project#5`) | `GET /users/me/projects`, `GET /users/{id}/projects` |

`UserRepository.existsByEmail` and `UserService.findOneByEmail` are declared but have no caller in
`src/main` today.

## Queries

### 1. All users

`UserRepository.findAll()` — Spring Data `findAll`, no ordering clause. Serves `GET /users` via
`UserService.findAll` (bare JSON array of `User.toJson()`). Unbounded by design (small table).

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users;
```

### 2. User by id

`UserRepository.findById(id)` — PK lookup behind `UserService.findOneById`. Missing →
`404 User with id "…" not found` on the user routes; on the JWT path `AuthService.validateUserById`
turns both "missing" and `is_active = false` into `401 Unauthorized`, and `POST /auth/refresh` into
`401 Account no longer exists` / inactive. `InvitationService.create` uses it to name the inviter in
the in-app notification.

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE id = :id;

-- :id = '11111111-1111-4111-8111-111111111111'
```

### 3. User by email

`UserRepository.findByEmail(email)` — derived query on the unique `email` column
(`UQ_users_email`). Three callers: `AuthService.login` (`UserService.findOneByEmailWithPassword`,
the one path that reads `password_hash`; unknown email → the same 401 as a wrong password),
`UserService.create` (pre-check → `409 User with email "…" already exists`), and
`InvitationService.create` (an existing account gets the in-app notification).

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE email = :email;

-- :email = 'alice@example.com'
```

### 4. Insert user

`UserService.create` (from `POST /auth/register`) → `userRepository.save(user)` after **#3**. The
`id` is generated in the application (`@UuidGenerator`), so Hibernate issues a plain INSERT — no
merge SELECT. Entity defaults fill `role` (`backend_developer`), `avatar_url` (the dicebear
initials URL) and `is_active` (`true`); timestamps come from the DB. A concurrent duplicate that
slips past #3 hits `UQ_users_email` → the same 409.

```sql
INSERT INTO users (id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at)
VALUES (:id, :email, :fullName, :passwordHash, 'backend_developer',
        'https://api.dicebear.com/9.x/initials/svg?seed=default', true, now(), now());

-- :passwordHash = '$2b$10$…' (bcrypt, cost 10)
```

### 5. Users by ids

`UserRepository.findByIdIn(ids)` — derived query. `TaskService.resolveUsers` validates assignee ids
(an unknown id → 404 there); `ProjectService.validateUsers` does the same for
`POST /projects/{id}/members` before the membership inserts (`project#7`).

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE id IN (:ids);

-- :ids = ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222')
```

### 6. Active user ids among ids

`UserRepository.findActiveIdsByIdIn(ids)` — JPQL
`select u.id from User u where u.id in :ids and u.isActive = true`. `MentionService` resolves
`@[uuid]` mentions parsed from sanitized comment HTML; inactive users are silently dropped.
Selects only the id column.

```sql
SELECT id
FROM users
WHERE id IN (:ids)
  AND is_active = true;
```

### 7. Active user ids by full name

`UserRepository.findActiveIdsByFullNameIn(names)` — JPQL
`select u.id from User u where u.fullName in :names and u.isActive = true`. Same caller as #6, for
`@Full Name` mentions. Exact, case-sensitive match on `full_name` (no index on that column).

```sql
SELECT id
FROM users
WHERE full_name IN (:names)
  AND is_active = true;

-- :names = ('Alice Example')
```

### 8. Projects of a user

`= project#5` — `ProjectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc(userId)`, JPQL +
`@EntityGraph({"project", "project.creator"})`. `UserService.findProjects(userId, callerId)` runs
**#2** (the user must exist) and then this query, mapping each row to
`{ ...project.toJson(true), role, joined_at }`. The self-only check (`callerId` must equal
`userId`, else `403 You can only view your own projects`) is the method's first statement and
issues no query.

```sql
-- = project.md #5
SELECT m.project_id, m.user_id, m.role, m.joined_at,
       p.id, p.name, p.tag, p.ticket_counter, p.description, p.created_by, p.created_at, p.updated_at,
       c.id, c.email, c.full_name, c.role, c.avatar_url, c.is_active, c.created_at, c.updated_at
FROM project_members m
LEFT JOIN projects p ON p.id = m.project_id
LEFT JOIN users    c ON c.id = p.created_by
WHERE m.user_id = :userId
ORDER BY m.joined_at DESC;
```

## Endpoint query sequences

`project#N` = query N of [project.md](project.md). The leading **#2** on each 🔒 row is the JWT
reload described above.

| Endpoint | Queries, in order |
|---|---|
| `GET /users` 🔒 | **#2** (JWT reload) → **#1** |
| `GET /users/{id}` 🔒 | **#2** (JWT reload) → **#2** (target user) |
| `GET /users/me/projects` 🔒 | **#2** (JWT reload) → **#2** (self) → **#8** (`project#5`) |
| `GET /users/{id}/projects` 🔒 | **#2** (JWT reload) → `403` with no further query when `id` is not the caller; otherwise **#2** → **#8** (`project#5`) |
| `POST /auth/register` | **#3** → **#4** (then the refresh-token insert, auth module) |
| `POST /auth/login` | **#3** (then the refresh-token insert, auth module) |
| `POST /auth/refresh` | refresh-token lookup/revoke (auth module) → **#2** |

🔒 = `@JwtAuth` (all four user routes since JAV-21). No `ProjectAccessService` gate here — user
routes are not project-scoped; the only authorization rule is the self-only check on
`GET /users/{id}/projects`.
