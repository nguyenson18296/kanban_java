# Project membership & access — raw PostgreSQL queries

Queries executed by `modules/project` for **project membership and authorization**:
`ProjectAccessService`, `ProjectAccessQueries` / `JpaProjectAccessQueries`, and
`ProjectMemberRepository`. Project CRUD itself (`ProjectRepository` / `ProjectService`
create, update, delete, find) is not documented yet — add it here when those paths change.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (aliases and column lists simplified so
  it pastes into `psql`); native queries are shown verbatim.
- Parameters are `:name`. Example values: project id `'UrzWUH3e'` (8 alphanumeric chars),
  user id `'11111111-1111-4111-8111-111111111111'` (uuid).
- Tables: `project_members (project_id VARCHAR(8), user_id UUID, role project_role, joined_at TIMESTAMPTZ, PK (project_id, user_id))`,
  index `idx_project_members_user_id (user_id)`. Enum `project_role`: `owner | admin | member | viewer`.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Membership lookup by PK | `ProjectMemberRepository.findByProjectIdAndUserId` | every `ProjectAccessService.ensureRole` / `getMembership` call (see [Endpoint sequences](#endpoint-query-sequences)) |
| 2 | Membership exists? | `ProjectMemberRepository.existsByProjectIdAndUserId` | `POST /projects/{projectId}/teams/{teamId}/members` |
| 3 | Project ids of a user | `ProjectMemberRepository.findProjectIdsByUserId` | Socket.IO connect (presence rooms); `ProjectAccessService.getProjectIdsForUser` |
| 4 | Members of a project + user | `ProjectMemberRepository.findByProjectIdWithUserOrderByJoinedAtAsc` | `GET /projects/{id}/members` |
| 5 | Projects of a user + project + creator | `ProjectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc` | `GET /users/me/projects`, `GET /users/{id}/projects` |
| 6 | Existing memberships among candidates | `ProjectMemberRepository.findByProjectIdAndUserIdIn` | `POST /projects/{id}/members` |
| 7 | Insert membership | `ProjectMemberRepository.save` / `saveAll` | `POST /projects` (creator → owner), `POST /projects/{id}/members` |
| 8 | Bulk delete memberships | `ProjectMemberRepository.deleteByProjectIdAndUserIdIn` | `DELETE /projects/{id}/members` |
| 9 | Project id of a task | `JpaProjectAccessQueries.findProjectIdForTask` | `ProjectAccessService.ensureTaskRole` / `getProjectIdForTask` (no route wired yet — RBAC Task 11) |
| 10 | Project id of a column | `JpaProjectAccessQueries.findProjectIdForColumn` | `ProjectAccessService.ensureColumnRole` / `getProjectIdForColumn` (no route wired yet — RBAC Task 10/11) |

## Queries

### 1. Membership lookup by primary key

`ProjectMemberRepository.findByProjectIdAndUserId(projectId, userId)` — derived query.
The single query behind the authorization gate: `ProjectAccessService.getMembership`,
`ensureRole`, `ensureTaskRole`, `ensureColumnRole`. Hits the PK index; returns 0 or 1 row.

```sql
SELECT project_id, user_id, role, joined_at
FROM project_members
WHERE project_id = :projectId
  AND user_id    = :userId;

-- :projectId = 'UrzWUH3e', :userId = '11111111-1111-4111-8111-111111111111'
```

No row → the service throws a masked 404 (`Project with id "UrzWUH3e" not found`), so a
non-member cannot tell whether the project exists. `role` below the required role → 403.

### 2. Membership exists?

`ProjectMemberRepository.existsByProjectIdAndUserId(projectId, userId)` — derived query.
`TeamService.addMember` uses it to refuse adding a non-project-member to a team.

```sql
SELECT count(*) > 0
FROM project_members
WHERE project_id = :projectId
  AND user_id    = :userId;
```

### 3. Project ids of a user

`ProjectMemberRepository.findProjectIdsByUserId(userId)` — JPQL
`select m.projectId from ProjectMember m where m.userId = :userId`.
Used by `PresenceService.handleConnectionOpened` (joins the socket to every `project:<id>`
room) and exposed as `ProjectAccessService.getProjectIdsForUser`. Uses `idx_project_members_user_id`.

```sql
SELECT project_id
FROM project_members
WHERE user_id = :userId;
```

### 4. Members of a project (with user)

`ProjectMemberRepository.findByProjectIdWithUserOrderByJoinedAtAsc(projectId)` — JPQL +
`@EntityGraph(attributePaths = "user")`, so the `users` row is fetched in the same query
(no N+1). Serves `GET /projects/{id}/members` via `ProjectService.getMembers`.

```sql
SELECT m.project_id, m.user_id, m.role, m.joined_at,
       u.id, u.email, u.full_name, u.role, u.avatar_url, u.is_active, u.created_at, u.updated_at
FROM project_members m
LEFT JOIN users u ON u.id = m.user_id
WHERE m.project_id = :projectId
ORDER BY m.joined_at ASC;
```

`password_hash` is selected by Hibernate (it is a mapped column) but never serialized —
`User.toJson()` omits it.

### 5. Projects of a user (with project and creator)

`ProjectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc(userId)` — JPQL +
`@EntityGraph(attributePaths = {"project", "project.creator"})`. Serves
`GET /users/me/projects` and `GET /users/{id}/projects` via `UserService.findProjects`.
One query, two joins.

```sql
SELECT m.project_id, m.user_id, m.role, m.joined_at,
       p.id, p.name, p.tag, p.ticket_counter, p.description, p.created_by, p.created_at, p.updated_at,
       c.id, c.email, c.full_name, c.role, c.avatar_url, c.is_active, c.created_at, c.updated_at
FROM project_members m
LEFT JOIN projects p ON p.id = m.project_id
LEFT JOIN users    c ON c.id = p.created_by
WHERE m.user_id = :userId
ORDER BY m.joined_at DESC;
```

### 6. Existing memberships among candidate users

`ProjectMemberRepository.findByProjectIdAndUserIdIn(projectId, userIds)` — derived query.
`ProjectService.addMembers` uses it to skip users who are already members (idempotent add).

```sql
SELECT project_id, user_id, role, joined_at
FROM project_members
WHERE project_id = :projectId
  AND user_id IN (:userIds);

-- :userIds = ('11111111-1111-4111-8111-111111111111', '22222222-2222-4222-8222-222222222222')
```

### 7. Insert membership

`ProjectService.create` → `memberRepository.saveAndFlush(new ProjectMember(projectId, actorId, OWNER))`
(inside the same `TransactionTemplate` as the project insert);
`ProjectService.addMembers` → `memberRepository.saveAll(members)` + `flush()`, role `member`.

```sql
INSERT INTO project_members (project_id, user_id, role, joined_at)
VALUES (:projectId, :userId, :role, now());

-- :role = 'owner' (project creator) | 'member' (POST /projects/{id}/members)
```

**Performance note:** `ProjectMember` has an assigned composite id (`@IdClass`) and no
`@Version`/`Persistable`, so Spring Data treats every instance as *not new* and calls
`em.merge`. Hibernate therefore runs query **#1 (SELECT by PK) before each INSERT** —
`addMembers` with N new users issues N selects + N inserts, after the one `IN` query (#6).
Acceptable at current sizes; if it becomes hot, implement `Persistable<ProjectMemberId>`
or switch to a native batch insert.

### 8. Bulk delete memberships

`ProjectMemberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds)` — JPQL
`@Modifying` bulk delete (its own `@Transactional`). `ProjectService.removeMembers` calls it
**after** `TeamMemberRepository.deleteByProjectIdAndUserIdIn` (users leaving a project also
leave its teams — see the team module's query doc). Returns the number of rows deleted.

```sql
DELETE FROM project_members
WHERE project_id = :projectId
  AND user_id IN (:userIds);
```

### 9. Project id of a task (native)

`JpaProjectAccessQueries.findProjectIdForTask(taskId)` — verbatim native SQL. Resolves a
task-scoped route to its project so `ensureTaskRole` can run query #1. A missing task and a
non-member both surface as `Task with id "<taskId>" not found`.

```sql
SELECT col.project_id
FROM tasks task
INNER JOIN kanban_columns col ON col.id = task.column_id
WHERE task.id = CAST(:taskId AS uuid);

-- :taskId = '33333333-3333-4333-8333-333333333333'
```

### 10. Project id of a column (native)

`JpaProjectAccessQueries.findProjectIdForColumn(columnId)` — verbatim native SQL; the
column-scoped counterpart of #9 for `ensureColumnRole`.

```sql
SELECT col.project_id
FROM kanban_columns col
WHERE col.id = :columnId;

-- :columnId = 7
```

## Endpoint query sequences

What actually hits the database per request, in order. `ProjectRepository.existsById`
(`SELECT 1 FROM projects WHERE id = :projectId`) belongs to project CRUD but is listed
because these endpoints run it first.

| Endpoint | Queries, in order |
|---|---|
| `POST /projects` | project INSERT (see project CRUD) → **#7** (`owner`, preceded by the merge SELECT) — one transaction |
| `GET /projects/{id}/members` | `existsById` → **#4** |
| `POST /projects/{id}/members` 🔒 | `existsById` → **#1** (gate, `admin`) → `users` lookup for the candidate ids → **#6** → **#7** ×N |
| `DELETE /projects/{id}/members` 🔒 | `existsById` → **#1** (gate, `admin`) → team-members DELETE → **#8** |
| `POST /projects/{projectId}/teams` 🔒 | **#1** (gate, `admin`) → team INSERT |
| `POST /projects/{projectId}/teams/{teamId}/members` 🔒 | **#1** (gate, `admin`) → team lookup → **#2** → team-member exists? (`findByTeamIdAndUserId`) → team-member INSERT |
| `DELETE /projects/{projectId}/teams/{teamId}/members/{userId}` 🔒 | **#1** (gate, `admin`) → team-member DELETE |
| `GET /users/me/projects` 🔒, `GET /users/{id}/projects` | **#5** |
| Socket.IO connect | **#3** (join `project:<id>` rooms) |

🔒 = `@JwtAuth`. Once routes carry `@RequireProjectRole` (RBAC Tasks 10–12), **#1** runs in
`ProjectRoleInterceptor` *before* the handler for every annotated route, and task/column
routes add **#9**/**#10** ahead of it.
