# Project invitations — raw PostgreSQL queries

Queries executed by `modules/invitation` (RBAC Tasks 8–9: create / list pending / revoke /
accept): `InvitationService`, `ProjectInvitationRepository`, plus the lookups it borrows
from `UserRepository`, `ProjectMemberRepository` and `ProjectRepository` (via
`ProjectService.findOneById`).

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (aliases and column lists simplified so
  it pastes into `psql`).
- Parameters are `:name`. Example values: project id `'UrzWUH3e'` (8 alphanumeric chars),
  invitation id / user id `'11111111-1111-4111-8111-111111111111'` (uuid),
  email `'jane@example.com'` (normalized: trimmed + lowercased in the service).
- Table: `project_invitations (id UUID PK, project_id VARCHAR(8) → projects ON DELETE CASCADE,
  email VARCHAR(255), role project_role, token_hash VARCHAR(64), invited_by UUID → users ON DELETE SET NULL,
  expires_at TIMESTAMPTZ, accepted_at TIMESTAMPTZ, accepted_by UUID, revoked_at TIMESTAMPTZ, created_at TIMESTAMPTZ)`.
  Indexes: `idx_project_invitations_token_hash` (unique), `idx_project_invitations_project_id`,
  `idx_project_invitations_email`.
- "Pending" always means `accepted_at IS NULL AND revoked_at IS NULL AND expires_at > now()`.
- Every project-scoped endpoint runs the membership gate first — query **#1 of
  [project.md](project.md)** (`project_members` PK lookup via `ProjectAccessService.ensureRole`);
  non-members get the masked project 404, so no separate `projects` existence check runs.
  `POST /invitations/accept` is the exception: the token itself is the credential, so it
  starts at the token-hash lookup (**#7**) instead of a role gate.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | User by email | `UserRepository.findByEmail` | `POST /projects/{id}/invitations` (already-member guard) |
| 2 | Pending invitation by project + email | `ProjectInvitationRepository.findPendingByProjectIdAndEmail` | `POST /projects/{id}/invitations` (duplicate guard) |
| 3 | Insert invitation | `ProjectInvitationRepository.saveAndFlush` | `POST /projects/{id}/invitations` |
| 4 | Pending invitations of a project + inviter | `ProjectInvitationRepository.findPendingByProjectId` | `GET /projects/{id}/invitations` |
| 5 | Revoke if still pending | `ProjectInvitationRepository.revokePending` | `DELETE /projects/{id}/invitations/{invitationId}` |
| 6 | Invitation by id + project | `ProjectInvitationRepository.findByIdAndProjectId` | `DELETE /projects/{id}/invitations/{invitationId}` (only when #5 matched no row) |
| 7 | Invitation by token hash | `ProjectInvitationRepository.findByTokenHash` | `POST /invitations/accept` |
| 8 | Insert membership | `ProjectMemberRepository.save` | `POST /invitations/accept` |
| 9 | Mark invitation accepted | `ProjectInvitationRepository.save` on the managed row | `POST /invitations/accept` |
| 10 | Project + creator | `ProjectRepository.findByIdWithCreator` (via `ProjectService.findOneById`) | `POST /invitations/accept` (response); `POST /projects/{id}/invitations` (notification payload, invitee has an account) |
| 11 | User by id | `UserRepository.findById` | `POST /projects/{id}/invitations` (inviter in the notification payload, invitee has an account) |

## Queries

### 1. User by email

`UserRepository.findByEmail(email)` — derived query. `InvitationService.create` uses it to
resolve the invitee's account (if any); when a user exists, their membership is checked via
project.md **#1** and an existing member gets a 409. Uses the `users.email` unique index.

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE email = :email;

-- :email = 'jane@example.com'
```

### 2. Pending invitation by project + email

`ProjectInvitationRepository.findPendingByProjectIdAndEmail(projectId, email, now)` — JPQL.
Duplicate guard in `InvitationService.create`: an existing pending invitation for the same
email → 409. Uses `idx_project_invitations_project_id` (or `..._email`).

```sql
SELECT id, project_id, email, role, token_hash, invited_by, expires_at,
       accepted_at, accepted_by, revoked_at, created_at
FROM project_invitations
WHERE project_id  = :projectId
  AND email       = :email
  AND accepted_at IS NULL
  AND revoked_at  IS NULL
  AND expires_at  > :now;

-- :projectId = 'UrzWUH3e', :email = 'jane@example.com', :now = now()
```

### 3. Insert invitation

`InvitationService.create` → `invitationRepository.saveAndFlush(invitation)`. The id is
generated app-side (`@UuidGenerator`, so this is a plain persist — no merge SELECT);
`created_at` comes from the DB default. Only the sha256 hex of the token is stored
(`token_hash`, unique index) — the raw token is returned once in the 201 body and never
persisted. `expires_at` is now + 7 days; `role` defaults to `member` when absent.

```sql
INSERT INTO project_invitations
  (id, project_id, email, role, token_hash, invited_by, expires_at,
   accepted_at, accepted_by, revoked_at, created_at)
VALUES
  (:id, :projectId, :email, :role, :tokenHash, :invitedBy, :expiresAt,
   NULL, NULL, NULL, now());

-- :role = 'admin' | 'member' | 'viewer' ('owner' is rejected at validation)
```

### 4. Pending invitations of a project + inviter

`ProjectInvitationRepository.findPendingByProjectId(projectId, now)` — JPQL with
`@EntityGraph("inviter")`: the response embeds `inviter` (`toJson(true)`), so the users row
joins in the same query (lazy relation + open-in-view off would otherwise throw). Newest
first. Uses `idx_project_invitations_project_id`.

```sql
SELECT i.id, i.project_id, i.email, i.role, i.token_hash, i.invited_by, i.expires_at,
       i.accepted_at, i.accepted_by, i.revoked_at, i.created_at,
       u.id, u.email, u.full_name, u.password_hash, u.role, u.avatar_url, u.is_active,
       u.created_at, u.updated_at
FROM project_invitations i
LEFT JOIN users u ON u.id = i.invited_by
WHERE i.project_id  = :projectId
  AND i.accepted_at IS NULL
  AND i.revoked_at  IS NULL
  AND i.expires_at  > :now
ORDER BY i.created_at DESC;
```

Unbounded by design for now: a project's pending set is small (duplicates are refused and
invitations expire after 7 days). Paginate if that assumption changes.

### 5. Revoke if still pending

`ProjectInvitationRepository.revokePending(id, projectId, revokedAt)` — JPQL `@Modifying`
update, the whole of `InvitationService.revoke`'s write. The `accepted_at IS NULL AND
revoked_at IS NULL` predicate lives in the statement rather than in Java, so a concurrent
accept cannot land between a read and the write: the row is claimed atomically and the
affected-row count decides the response. `project_id` is in the predicate so an invitation
id from another project matches nothing. PK lookup.

```sql
UPDATE project_invitations
SET revoked_at = :revokedAt
WHERE id          = :id
  AND project_id  = :projectId
  AND accepted_at IS NULL
  AND revoked_at  IS NULL;

-- :id = '11111111-1111-4111-8111-111111111111', :projectId = 'UrzWUH3e', :revokedAt = now()
```

1 row → 204. 0 rows → query **#6** decides between 404, 409 and the idempotent 204.

### 6. Invitation by id + project

`ProjectInvitationRepository.findByIdAndProjectId(id, projectId)` — derived query. Runs
**only when #5 matched no row**, to tell the three no-op cases apart: row missing →
404 `Invitation not found`; `accepted_at` set → 409 `Invitation has already been accepted`;
`revoked_at` set → 204 (revoke is idempotent). Read-only — nothing is written on this path,
so an accept that won the race keeps its `accepted_at` / `accepted_by`.

```sql
SELECT id, project_id, email, role, token_hash, invited_by, expires_at,
       accepted_at, accepted_by, revoked_at, created_at
FROM project_invitations
WHERE id         = :id
  AND project_id = :projectId;
```

### 7. Invitation by token hash

`ProjectInvitationRepository.findByTokenHash(tokenHash)` — derived query. The entry point of
`InvitationService.accept`: the raw token from the request body is sha256-hashed in the
service and looked up on the unique `idx_project_invitations_token_hash`. A miss — and every
other invalid condition checked in Java afterwards (expired, revoked, already accepted,
email ≠ the logged-in user's) — throws the same generic 400, so the endpoint is not an
oracle for token validity or invitee emails.

```sql
SELECT id, project_id, email, role, token_hash, invited_by, expires_at,
       accepted_at, accepted_by, revoked_at, created_at
FROM project_invitations
WHERE token_hash = :tokenHash;

-- :tokenHash = sha256 hex of the raw token (64 chars)
```

### 8. Insert membership (accept)

`InvitationService.accept` → `memberRepository.save(new ProjectMember(projectId, userId, role))`,
inside the method's `@Transactional`. Same statement as project.md **#7**, and the same
performance note applies: `ProjectMember`'s assigned composite id makes `save` go through
`em.merge`, so Hibernate runs the project.md **#1** SELECT before this INSERT.

```sql
INSERT INTO project_members (project_id, user_id, role, joined_at)
VALUES (:projectId, :userId, :role, now());

-- :role = the invitation's role ('admin' | 'member' | 'viewer')
```

### 9. Mark invitation accepted

`InvitationService.accept` → `invitationRepository.save(invitation)` on the row loaded by
**#7**. Unlike revoke's detached case (#5/#6), the entity stays managed inside `accept`'s
`@Transactional`, so there is no extra merge SELECT — the dirty check at commit emits one
UPDATE. Committed atomically with **#8**: the membership insert and the accepted mark
succeed or roll back together.

```sql
UPDATE project_invitations
SET accepted_at = :acceptedAt,
    accepted_by = :userId
WHERE id = :id;

-- :acceptedAt = now(), :userId = the accepting user's uuid
```

### 10. Project + creator

`ProjectRepository.findByIdWithCreator(id)` (project CRUD, via `ProjectService.findOneById`)
— JPQL with `@EntityGraph("creator")`. `accept` returns it as the response body
(`toJson(true)` embeds the creator); `create` fetches it for the notification payload's
`project_name` when the invitee already has an account. Unknown id → the project-flavored
404 (cannot happen from these callers: the FK guarantees the project exists).

```sql
SELECT p.*, u.*
FROM projects p
LEFT JOIN users u ON u.id = p.created_by
WHERE p.id = :id;
```

### 11. User by id

`UserRepository.findById(actorId)` — PK lookup. Fetches the inviter for the notification
payload (`inviter: { id, full_name, avatar_url }`) when the invitee has an account.

```sql
SELECT id, email, full_name, password_hash, role, avatar_url, is_active, created_at, updated_at
FROM users
WHERE id = :id;
```

## Endpoint query sequences

What actually hits the database per request, in order. `project#1` = the membership gate
(query #1 of [project.md](project.md)).

| Endpoint | Queries, in order |
|---|---|
| `POST /projects/{id}/invitations` 🔒 | `project#1` (gate: `admin`, or `owner` when inviting an `admin`) → **#1** → `project#1` (invitee membership; only when #1 found a user) → **#2** → **#3** → **#10** + **#11** (notification payload; only when #1 found a user) |
| `GET /projects/{id}/invitations` 🔒 | `project#1` (gate, `admin`) → **#4** |
| `DELETE /projects/{id}/invitations/{invitationId}` 🔒 | `project#1` (gate, `admin`) → **#5**; **#6** only when #5 affected 0 rows (success is a single UPDATE) |
| `POST /invitations/accept` 🔒 | one transaction: **#7** → `project#1` (already-member check) → **#8** (merge SELECT + INSERT) → **#10** (response) → **#9** (UPDATE at commit) |

🔒 = `@JwtAuth`.

The `PROJECT_INVITED` emit in `create` is fire-and-forget: the async listeners run after the
request, `NotificationListener` inserting the notification row (notification module) and
`EventsService` pushing the Socket.IO event (no DB).
