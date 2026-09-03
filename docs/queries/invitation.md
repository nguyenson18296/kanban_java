# Project invitations — raw PostgreSQL queries

Queries executed by `modules/invitation` (RBAC Task 8: create / list pending / revoke):
`InvitationService`, `ProjectInvitationRepository`, plus the user lookup it borrows from
`UserRepository`. The accept flow (token lookup, membership insert) is RBAC Task 9 — add
its queries here when it lands.

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
- Every endpoint runs the membership gate first — query **#1 of
  [project.md](project.md)** (`project_members` PK lookup via `ProjectAccessService.ensureRole`);
  non-members get the masked project 404, so no separate `projects` existence check runs.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | User by email | `UserRepository.findByEmail` | `POST /projects/{id}/invitations` (already-member guard) |
| 2 | Pending invitation by project + email | `ProjectInvitationRepository.findPendingByProjectIdAndEmail` | `POST /projects/{id}/invitations` (duplicate guard) |
| 3 | Insert invitation | `ProjectInvitationRepository.saveAndFlush` | `POST /projects/{id}/invitations` |
| 4 | Pending invitations of a project + inviter | `ProjectInvitationRepository.findPendingByProjectId` | `GET /projects/{id}/invitations` |
| 5 | Revoke if still pending | `ProjectInvitationRepository.revokePending` | `DELETE /projects/{id}/invitations/{invitationId}` |
| 6 | Invitation by id + project | `ProjectInvitationRepository.findByIdAndProjectId` | `DELETE /projects/{id}/invitations/{invitationId}` (only when #5 matched no row) |

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

## Endpoint query sequences

What actually hits the database per request, in order. `project#1` = the membership gate
(query #1 of [project.md](project.md)).

| Endpoint | Queries, in order |
|---|---|
| `POST /projects/{id}/invitations` 🔒 | `project#1` (gate: `admin`, or `owner` when inviting an `admin`) → **#1** → `project#1` (invitee membership; only when #1 found a user) → **#2** → **#3** |
| `GET /projects/{id}/invitations` 🔒 | `project#1` (gate, `admin`) → **#4** |
| `DELETE /projects/{id}/invitations/{invitationId}` 🔒 | `project#1` (gate, `admin`) → **#5**; **#6** only when #5 affected 0 rows (success is a single UPDATE) |

🔒 = `@JwtAuth`.
