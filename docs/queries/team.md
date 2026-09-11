# Teams — raw PostgreSQL queries

Queries executed by `modules/team` (`TeamController` / `TeamService` / `TeamRepository` /
`TeamMemberRepository`). Every route is `@JwtAuth` and gates on project membership through
`ProjectAccessService`: since RBAC Task 12 (JAV-21) the three reads carry
`@RequireProjectRole(VIEWER)`, so `ProjectRoleInterceptor` runs the gate *before* the handler;
the mutating routes call `ensureRole(ADMIN)` in the service. The gate queries themselves live in
[project.md](project.md) and are cross-referenced here as `project#N`.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (column lists simplified so it pastes into `psql`).
- Parameters are `:name`. Example values: team id `7` (serial int), project id `'UrzWUH3e'`
  (8 alphanumeric chars), user id `'11111111-1111-4111-8111-111111111111'` (uuid).
- Tables: `teams (id SERIAL PK, name VARCHAR(100), description TEXT, color VARCHAR(20),
  is_active BOOLEAN, project_id VARCHAR(8) → projects ON DELETE CASCADE, created_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ, UNIQUE (project_id, name))`, indexes `idx_teams_project_id`, `idx_teams_is_active`;
  `team_members (team_id INT → teams ON DELETE CASCADE, user_id UUID → users ON DELETE CASCADE,
  project_id VARCHAR(8), joined_at TIMESTAMPTZ, PK (team_id, user_id), UNIQUE (user_id, project_id))`,
  indexes `idx_team_members_user_id`, `idx_team_members_project_id`.
- **Access gates** (from project.md): `project#1` = membership PK lookup (`ensureRole`), `project#2` =
  membership exists? (`existsByProjectIdAndUserId`). A non-member gets the masked
  `404 Project with id "UrzWUH3e" not found` — identical to a missing project — before any team row is
  read; an admin-only route returns 403 to `member`/`viewer`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Project exists? | `ProjectRepository.existsById` (via `TeamService.ensureProjectExists`) | `POST /projects/{projectId}/teams`, `GET /projects/{projectId}/teams` |
| 2 | Teams of a project | `TeamRepository.findByProjectIdOrderByCreatedAtAsc` | `GET /projects/{projectId}/teams` |
| 3 | Team by id within a project | `TeamRepository.findByIdAndProjectId` | `GET /…/teams/{teamId}`, `GET /…/teams/{teamId}/members`, `POST /…/teams/{teamId}/members`, `DELETE /…/teams/{teamId}/members/{userId}`, `POST /…/teams` (re-fetch) |
| 4 | Insert team | `TeamRepository.saveAndFlush` (new) | `POST /projects/{projectId}/teams` |
| 5 | Team members + user | `TeamMemberRepository.findByTeamIdAndProjectIdWithUserOrderByJoinedAtAsc` | `GET /…/teams/{teamId}/members` |
| 6 | Team membership by PK | `TeamMemberRepository.findByTeamIdAndUserId` | `POST /…/teams/{teamId}/members` (idempotency check, plus the merge SELECT before #7) |
| 7 | Insert team membership | `TeamMemberRepository.saveAndFlush` (new) | `POST /…/teams/{teamId}/members` |
| 8 | Delete one team membership | `TeamMemberRepository.deleteByTeamIdAndUserId` | `DELETE /…/teams/{teamId}/members/{userId}` |
| 9 | Bulk delete team memberships | `TeamMemberRepository.deleteByProjectIdAndUserIdIn` | `DELETE /projects/{id}/members` (`ProjectService.removeMembers`) |

## Queries

### 1. Project exists?

`ProjectRepository.existsById(projectId)` — Spring Data `existsById`, run by the private
`TeamService.ensureProjectExists`. Missing → `404 Project with id "UrzWUH3e" not found` (the same
body the gate uses for non-members, so it is not an existence oracle). On `GET /teams` it runs
*after* the interceptor gate, which already implies the project exists (a membership row references
it) — redundant, kept because JAV-21 changed no service code.

```sql
SELECT count(*)
FROM projects
WHERE id = :projectId;

-- :projectId = 'UrzWUH3e'
```

### 2. Teams of a project

`TeamRepository.findByProjectIdOrderByCreatedAtAsc(projectId)` — derived query. Serves
`GET /projects/{projectId}/teams` via `TeamService.findAllByProject`. Uses `idx_teams_project_id`.

```sql
SELECT id, name, description, color, is_active, project_id, created_at, updated_at
FROM teams
WHERE project_id = :projectId
ORDER BY created_at ASC;
```

### 3. Team by id within a project

`TeamRepository.findByIdAndProjectId(teamId, projectId)` — derived query behind
`TeamService.findOneById`. Scoping by `project_id` means a team id from *another* project is a
404, not a leak. Missing → `404 Team with id "7" not found in project "UrzWUH3e"`.

```sql
SELECT id, name, description, color, is_active, project_id, created_at, updated_at
FROM teams
WHERE id = :teamId
  AND project_id = :projectId;

-- :teamId = 7, :projectId = 'UrzWUH3e'
```

### 4. Insert team

`TeamService.create` → `saveAndFlush(new team)` after **#1** and `project#1` (`ensureRole` `admin`).
`id` is DB-generated (serial, `GenerationType.IDENTITY`); `is_active` defaults to `true` in the
entity. Unique `(project_id, name)` violation → `409 Team "<name>" already exists in this project`.
The service then re-reads the row with **#3** so the response carries the DB-generated timestamps.

```sql
INSERT INTO teams (name, description, color, is_active, project_id, created_at, updated_at)
VALUES (:name, :description, :color, true, :projectId, now(), now())
RETURNING id;
```

### 5. Team members (with user)

`TeamMemberRepository.findByTeamIdAndProjectIdWithUserOrderByJoinedAtAsc(teamId, projectId)` — JPQL
`select m from TeamMember m where m.teamId = :teamId and m.projectId = :projectId order by m.joinedAt asc`
+ `@EntityGraph(attributePaths = "user")`, so the `users` row comes in the same query (no N+1).
Serves `GET /…/teams/{teamId}/members` via `TeamService.getMembers` (`TeamMember.toJsonWithUser`).

```sql
SELECT m.team_id, m.user_id, m.project_id, m.joined_at,
       u.id, u.email, u.full_name, u.role, u.avatar_url, u.is_active, u.created_at, u.updated_at
FROM team_members m
LEFT JOIN users u ON u.id = m.user_id
WHERE m.team_id    = :teamId
  AND m.project_id = :projectId
ORDER BY m.joined_at ASC;
```

`password_hash` is selected by Hibernate (it is a mapped column) but never serialized —
`User.toJson()` omits it.

### 6. Team membership by primary key

`TeamMemberRepository.findByTeamIdAndUserId(teamId, userId)` — derived query. `TeamService.addMember`
uses it to make the add idempotent (already a member → return without writing). Hibernate runs
the same SELECT once more as the merge check before **#7** (see the note there).

```sql
SELECT team_id, user_id, project_id, joined_at
FROM team_members
WHERE team_id = :teamId
  AND user_id = :userId;

-- :teamId = 7, :userId = '22222222-2222-4222-8222-222222222222'
```

### 7. Insert team membership

`TeamService.addMember` → `saveAndFlush(new TeamMember(teamId, userId, projectId))`, after
`project#1` (`admin`), **#3**, `project#2` (the target must already be a *project* member, else
`400 User "…" is not a member of project "…"`) and **#6**.

```sql
INSERT INTO team_members (team_id, user_id, project_id, joined_at)
VALUES (:teamId, :userId, :projectId, now());
```

**Performance note:** `TeamMember` has an assigned composite id (`@IdClass`) and no
`@Version`/`Persistable`, so Spring Data treats it as *not new* and calls `em.merge`: Hibernate
runs **#6** (SELECT by PK) before the INSERT — two round trips per add, same trade-off as
`project_members` (project.md #7).

Unique `(user_id, project_id)` violation (`UQ_team_members_user_id_project_id`) →
`409 User is already assigned to a team in this project`: a user belongs to at most one team per
project. The service matches the constraint name on `user_id`.

### 8. Delete one team membership

`TeamMemberRepository.deleteByTeamIdAndUserId(teamId, userId)` — JPQL `@Modifying` bulk delete (its
own `@Transactional`). `TeamService.removeMember` runs it after `project#1` (`admin`) and **#3**;
a user who was not in the team still gets 204 (0 rows deleted, no membership existence check).

```sql
DELETE FROM team_members
WHERE team_id = :teamId
  AND user_id = :userId;
```

### 9. Bulk delete team memberships (users leaving a project)

`TeamMemberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds)` — JPQL `@Modifying` bulk
delete. Not reachable from a team route: `ProjectService.removeMembers` (`DELETE /projects/{id}/members`,
inside its transaction) runs it right before `project#8` so users who leave a project also leave its
teams. `idx_team_members_project_id` serves the `project_id` predicate.

```sql
DELETE FROM team_members
WHERE project_id = :projectId
  AND user_id IN (:userIds);

-- :userIds = ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222')
```

## Endpoint query sequences

`project#N` = query N of [project.md](project.md) (the membership gate). Every 🔒 route first
reloads the caller (`user#2`, see [user.md](user.md)) — not repeated below.

| Endpoint | Queries, in order |
|---|---|
| `POST /projects/{projectId}/teams` 🔒 | **#1** → `project#1` (gate, `admin`) → **#4** → **#3** (re-fetch) |
| `GET /projects/{projectId}/teams` 🔒 | `project#1` (interceptor gate, `viewer`) → **#1** (redundant) → **#2** |
| `GET /projects/{projectId}/teams/{teamId}` 🔒 | `project#1` (interceptor gate, `viewer`) → **#3** |
| `GET /projects/{projectId}/teams/{teamId}/members` 🔒 | `project#1` (interceptor gate, `viewer`) → **#3** → **#5** |
| `POST /projects/{projectId}/teams/{teamId}/members` 🔒 | `project#1` (gate, `admin`) → **#3** → `project#2` → **#6** → **#6** (merge SELECT) → **#7** |
| `DELETE /projects/{projectId}/teams/{teamId}/members/{userId}` 🔒 | `project#1` (gate, `admin`) → **#3** → **#8** |
| `DELETE /projects/{id}/members` 🔒 (project module) | … → **#9** → `project#8` (see project.md) |

🔒 = `@JwtAuth`. The three reads gate through `@RequireProjectRole(VIEWER)` → `ProjectRoleInterceptor`
(the class-level `/projects/{projectId}/teams` mapping supplies the `projectId` path variable the
interceptor reads by default); the mutating routes gate in `TeamService` via `ensureRole(ADMIN)`. Both
run the same `project#1` query.
