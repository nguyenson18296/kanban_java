# Project Membership & RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the existing project-membership scaffolding into a trustworthy, server-enforced authorization system: a VIEWER role, role management with last-owner protection, hashed invitation tokens with expiry/revocation, a central authorization gate used by every module, membership-scoped queries on every project-scoped route, and authorized netty-socketio board rooms.

**Architecture:** A new `ProjectAccessService` (in `com.kanban.modules.project`) becomes the single authorization gate — it masks non-membership as 404 (anti-enumeration) and insufficient role as 403, and resolves task/column → project for entity-scoped checks. A `RequireProjectRole` annotation + `ProjectRoleInterceptor` (`modules/project/guards/`, following the `@JwtAuth`/`JwtAuthInterceptor` pattern) centralizes checks for routes with a project id in the path; entity-scoped routes (tasks, comments, subscriptions, activities) call the same service from their services. Invitations are a new feature package (`modules/invitation`) with sha256-hashed single-use tokens. The netty-socketio `EventsGateway` gains membership-checked `project:{id}` rooms.

**Tech Stack:** Spring Boot 3.5, Spring Data JPA, PostgreSQL (Flyway), netty-socketio, springdoc, JUnit 5 + Mockito + AssertJ, Maven.

**Spec:** See "Appendix: Source Spec & Design Decisions" at the bottom of this document — the plan argues from it.

> **Note:** a reviewed reference implementation of Tasks 4–9 exists on branch feat/project-membership-rbac.

## Global Constraints

Copied from `.claude/rules/git.md` and this port's established idioms (`MIGRATION.md` §1, `constraints.md`) — every task implicitly includes these:

- **Maven only** — never gradle. Tests: `mvn test -Dtest='XTest'`. Build: `mvn -q compile`. Lint: (no linter configured — skip).
- **snake_case everywhere on the wire** — JSON fields, DTO fields, `@Column` names (`token_hash`, `expires_at`, `user_id`). WS event payloads are the known camelCase surface (`projectId`) — follow that only inside `modules/events/`.
- **Throw, don't catch in controllers.** Services throw typed exceptions from `com.kanban.common.exception.*`: 404 `NotFoundException`, 403 `ForbiddenException`, 409 `ConflictException`, 400 `BadRequestException`, 401 `UnauthorizedException`.
- **Never leak internals**: no raw exception messages in response bodies. New/rewritten service methods throw directly with generic messages built via `Json.map(...)` and log with SLF4J `log.error(...)` — do NOT copy the legacy try/catch-with-error-body pattern even when editing next to it. Where the plan says to change a legacy rethrow guard, use `catch (HttpException e) { throw e; }`.
- **Swagger on everything**: springdoc `@Tag` per controller, `@Operation` + `@ApiResponse` per route, `@Parameter` for path params, `@JwtAuth`/`@SecurityRequirement` on guarded routes, `@Schema` on every DTO field.
- **Validation only via `ValidatedDto` DTOs** + `@ValidatedBody` (checks in `common/validation`: `@IsUUID`, `@IsArray`, `@IsEmail`, `@IsOptional`, `@IsEnum`, `@Length`, …; public snake_case fields). Never validate request shapes manually.
- **Pipes**: project ids → `@Param(pipe = Pipes.PROJECT_ID)`, UUIDs → `Pipes.UUID`, int ids → `Pipes.INT`.
- **List responses** use `ApiListResponse<T>` = `{ data, status, success, message? }` (`com.kanban.common.api.ApiListResponse`).
- **Data access**: Spring Data `XRepository` + repository API; a `*Queries` interface + `Jpa*Queries` impl only for joins the repository API can't express (pattern: `ProjectAccessQueries`/`JpaProjectAccessQueries`); multi-row writes that must stay consistent go on an `@Transactional` service method.
- **Logging**: one SLF4J `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` per service/gateway/listener; no `System.out`/`println`.
- **Migrations**: shipped as raw-SQL Flyway files in `src/main/resources/db/migration/` (`V2__*.sql`, `V3__*.sql`, …).
- **Git**: work on `feat/project-rbac` branched off `develop` (this repo's integration branch — `main` is production). Conventional Commits (`feat(project): ...`). Never commit to `main` or `develop`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Do not push or open a PR without the user's go-ahead.

## Authorization model (canonical — all tasks conform to this)

Roles, ordered: `owner (3) > admin (2) > member (1) > viewer (0)`.

| Action | Minimum role |
|---|---|
| View project, board, tasks, comments, activities, members, teams; subscribe to a task; join WS board room | any membership (`viewer`) |
| Create/update/move/reorder/delete tasks & subtasks; manage assignees/labels on a task; comment | `member` |
| Update project; add members; remove member/viewer members; create/list/revoke invitations (member/viewer); manage teams; manage columns | `admin` |
| Delete project; change roles touching `owner`/`admin` (either direction); remove `owner`/`admin` members; invite as `admin` | `owner` |

Cross-cutting rules:
- **404 masking**: a caller with NO membership in a project gets `404 Project with id "X" not found` from every project-scoped route — never a 403. A member below the required role gets `403 This action requires at least <role> role`.
- **No self role change.** Role changes/removals that would leave zero owners → `409 A project must have at least one owner`. Self-leave (removing exactly yourself) is allowed for any role, subject to the last-owner rule.
- **Invitations**: raw token (64 hex chars) returned exactly once at creation; only the sha256 hash is stored. 7-day expiry. Acceptance requires JWT + the invitee email matching the authed user's email; every invalid-token condition (unknown, expired, revoked, used, wrong email) returns the same generic `400 Invalid or expired invitation`. There is no mailer in this repo — delivery is out-of-band (frontend copies an invite link) plus an in-app notification when the invitee already has an account.
- **Owner cannot be granted by invitation** — only via role change by an existing owner.

---

## Task 0: Branch setup

> ✔ Equivalent already done if you work on an existing feature branch.

- [ ] **Step 1: Create the feature branch**

```bash
git checkout develop
git pull
git checkout -b feat/project-rbac
```

Expected: on branch `feat/project-rbac`, clean tree (any stray local doc edits — e.g. `MIGRATION.md`/`README.md` — may show as modified from before; leave them unstaged, never sweep them into feature commits).

---

## Task 1: VIEWER role + shared role hierarchy + migration

> ✔ Already implemented in this repo — verify, don't re-create.

**Files:**
- Already present: `src/main/java/com/kanban/modules/project/ProjectRole.java` (VIEWER value + `rank()` hierarchy)
- Already present: `src/main/java/com/kanban/modules/project/ProjectService.java` — calls `ProjectRole.rank()` / `projectAccessService.ensureRole(...)` directly; no local hierarchy constant was ever duplicated in this port
- Already present: `src/main/java/com/kanban/modules/team/TeamService.java` — same, no duplicated hierarchy to delete
- No new migration file: folded into `src/main/resources/db/migration/V1__baseline_schema.sql`, which already defines `project_role` with `'viewer'` included

**Interfaces:**
- Produces: `ProjectRole.VIEWER` (wire value `"viewer"`, via `@JsonValue`); `int ProjectRole.rank()` — the per-constant hierarchy value (`OWNER=3, ADMIN=2, MEMBER=1, VIEWER=0`), replacing the plan's `PROJECT_ROLE_HIERARCHY` map. Every later task compares `role.rank()` directly rather than indexing a separate map.

- [ ] **Step 1: Verify the enum and hierarchy**

`src/main/java/com/kanban/modules/project/ProjectRole.java`:

```java
package com.kanban.modules.project;

import com.fasterxml.jackson.annotation.JsonValue;
import com.kanban.common.validation.WireEnum;

public enum ProjectRole implements WireEnum {
  OWNER("owner", 3),
  ADMIN("admin", 2),
  MEMBER("member", 1),
  VIEWER("viewer", 0);

  private final String value;
  private final int rank;

  ProjectRole(String value, int rank) {
    this.value = value;
    this.rank = rank;
  }

  @Override
  @JsonValue
  public String value() {
    return value;
  }

  /** PROJECT_ROLE_HIERARCHY */
  public int rank() {
    return rank;
  }
}
```

- [ ] **Step 2: Verify no private hierarchy copy exists**

Unlike the NestJS original, this port's `ProjectService` and `TeamService` never declared a local `ROLE_HIERARCHY` map — every role check already calls `projectAccessService.ensureRole(...)` or compares `actor.getRole().rank() < requiredRole.rank()` directly against the enum. Confirm with:

```bash
grep -rn "ROLE_HIERARCHY" src/main/java/com/kanban/modules/project/ProjectService.java src/main/java/com/kanban/modules/team/TeamService.java
```

Expected: no matches.

- [ ] **Step 3: Verify the migration**

No new migration is needed. `src/main/resources/db/migration/V1__baseline_schema.sql` already creates the enum with `'viewer'` included:

```sql
CREATE TYPE project_role AS ENUM ('owner','admin','member','viewer');
```

(The same file also carries a defensive `ALTER TYPE project_role ADD VALUE IF NOT EXISTS 'viewer';` — belt-and-suspenders for the case the baseline is applied over an older snapshot, not a separate versioned migration.)

- [ ] **Step 4: Verify it compiles and existing tests pass**

```bash
mvn -q compile && mvn test
```

Expected: build succeeds; all existing suites pass (no behavior changed).

- [ ] **Step 5: Commit**

This task is already satisfied by the code at `HEAD` — `ProjectRole.java` shipped in the baseline commit (`feat(core): initial Spring Boot port of the Kanban NestJS backend`), not a separate task commit. If you find drift (e.g. a stray local hierarchy constant reintroduced) and need to fix it, commit with:

```bash
git add src/main/java/com/kanban/modules/project/ProjectRole.java src/main/java/com/kanban/modules/project/ProjectService.java src/main/java/com/kanban/modules/team/TeamService.java
git commit -m "feat(project): add viewer role and shared role hierarchy

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 2: `ProjectAccessService` — the central authorization gate

> ✔ Already implemented in this repo — verify, don't re-create.

**Files:**
- Already present: `src/main/java/com/kanban/modules/project/ProjectAccessService.java`
- Already present: `src/test/java/com/kanban/modules/project/ProjectAccessServiceTest.java`
- No module file to modify: Spring has no NestJS-style module registration — `ProjectAccessService` is a plain `@Service` bean, constructor-injected and auto-detected by component scanning from `KanbanApplication` (`@SpringBootApplication`). It depends on `ProjectMemberRepository` (Spring Data) and `ProjectAccessQueries`/`JpaProjectAccessQueries` (`src/main/java/com/kanban/modules/project/ProjectAccessQueries.java`, `JpaProjectAccessQueries.java`) in place of the plan's raw `DataSource` query builder.

**Interfaces:**
- Consumes: `ProjectMember`, `ProjectRole` (Task 1), `ProjectMemberRepository`, `ProjectAccessQueries`.
- Produces (used by every later task) — the real signatures exceed the plan's spec:
  - `ProjectMember getMembership(String projectId, String userId)`
  - `ProjectMember ensureRole(String projectId, String userId, ProjectRole minimumRole)` — 404 if not a member (masking), 403 if below role, returns the membership.
  - `List<String> getProjectIdsForUser(String userId)`
  - `String getProjectIdForTask(String taskId)` — 404 `Task with id "X" not found` if no such task.
  - `String getProjectIdForColumn(int columnId)` — 404 `Column with id "X" not found`.
  - `String ensureTaskRole(String taskId, String userId, ProjectRole minimumRole)` — combines the two; returns the project id. Inlines its own membership/role check (rather than delegating to `ensureRole`) so a non-member sees the task-flavored 404 (`Task with id "X" not found`), never the project-flavored message.
  - `String ensureColumnRole(int columnId, String userId, ProjectRole minimumRole)` — the column-scoped counterpart of `ensureTaskRole`, not specified by the plan; added because column-scoped routes (e.g. reordering/renaming a column) need the same task-flavored (here, column-flavored) 404 masking. Keep it when porting later tasks that touch columns.

- [ ] **Step 1: Verify the tests**

`src/test/java/com/kanban/modules/project/ProjectAccessServiceTest.java` (JUnit 5 + Mockito + AssertJ port of `project-access.service.spec.ts`):

```java
package com.kanban.modules.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Port of project-access.service.spec.ts */
class ProjectAccessServiceTest {
  private ProjectMemberRepository memberRepository;
  private ProjectAccessQueries queries;
  private ProjectAccessService service;

  private static ProjectMember membership(ProjectRole role) {
    return new ProjectMember("proj1234", "user-1", role);
  }

  private static Map<String, Object> response(HttpException e) {
    @SuppressWarnings("unchecked")
    Map<String, Object> body = (Map<String, Object>) e.getResponse();
    return body;
  }

  @BeforeEach
  void setUp() {
    memberRepository = mock(ProjectMemberRepository.class);
    queries = mock(ProjectAccessQueries.class);
    service = new ProjectAccessService(memberRepository, queries);
  }

  @Nested
  class EnsureRole {
    @Test
    @DisplayName("throws NotFoundException (masking) when the user is not a member")
    void masksNonMembers() {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Project with id \"proj1234\" not found"));
    }

    @Test
    @DisplayName("throws ForbiddenException when the member is below the required role")
    void forbidsBelowRole() {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }

    @ParameterizedTest(name = "allows {0} when {1} is required")
    @CsvSource({"VIEWER,VIEWER", "MEMBER,MEMBER", "ADMIN,MEMBER", "OWNER,ADMIN", "OWNER,OWNER"})
    void allows(ProjectRole has, ProjectRole needs) {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.of(membership(has)));
      assertThat(service.ensureRole("proj1234", "user-1", needs).getRole()).isEqualTo(has);
    }

    @ParameterizedTest(name = "rejects {0} when {1} is required")
    @CsvSource({"MEMBER,ADMIN", "ADMIN,OWNER", "VIEWER,OWNER"})
    void rejects(ProjectRole has, ProjectRole needs) {
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1")).thenReturn(Optional.of(membership(has)));
      assertThatThrownBy(() -> service.ensureRole("proj1234", "user-1", needs)).isInstanceOf(ForbiddenException.class);
    }
  }

  @Nested
  class GetProjectIdForTask {
    @Test
    @DisplayName("returns the project id resolved through the task column")
    void resolves() {
      when(queries.findProjectIdForTask("task-uuid")).thenReturn(Optional.of("proj1234"));
      assertThat(service.getProjectIdForTask("task-uuid")).isEqualTo("proj1234");
      verify(queries).findProjectIdForTask("task-uuid");
    }

    @Test
    @DisplayName("throws NotFoundException when the task does not exist")
    void notFound() {
      when(queries.findProjectIdForTask("missing")).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.getProjectIdForTask("missing"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Task with id \"missing\" not found"));
    }
  }

  @Test
  @DisplayName("getProjectIdsForUser returns the project ids of all memberships")
  void getProjectIdsForUser() {
    when(memberRepository.findProjectIdsByUserId("user-1")).thenReturn(List.of("p1", "p2"));
    assertThat(service.getProjectIdsForUser("user-1")).containsExactly("p1", "p2");
  }

  @Nested
  class GetProjectIdForColumn {
    @Test
    @DisplayName("returns the project id resolved through the column")
    void resolves() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      assertThat(service.getProjectIdForColumn(7)).isEqualTo("proj1234");
      verify(queries).findProjectIdForColumn(7);
    }

    @Test
    @DisplayName("throws NotFoundException when the column does not exist")
    void notFound() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.getProjectIdForColumn(7))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Column with id \"7\" not found"));
    }
  }

  @Nested
  class EnsureTaskRole {
    @Test
    @DisplayName("resolves the project then enforces the role, returning the project id")
    void resolves() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.MEMBER)));
      assertThat(service.ensureTaskRole("task-uuid", "user-1", ProjectRole.MEMBER)).isEqualTo("proj1234");
    }

    @Test
    @DisplayName("masks non-membership as a task-not-found 404 (never the project-flavored message)")
    void masks() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureTaskRole("task-uuid", "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Task with id \"task-uuid\" not found"));
    }

    @Test
    @DisplayName("still throws ForbiddenException for a member below the required role")
    void forbids() {
      when(queries.findProjectIdForTask(any())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any()))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureTaskRole("task-uuid", "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }
  }

  @Nested
  class EnsureColumnRole {
    @Test
    @DisplayName("masks non-membership as a column-not-found 404 (never the project-flavored message)")
    void masks() {
      when(queries.findProjectIdForColumn(anyInt())).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any())).thenReturn(Optional.empty());
      assertThatThrownBy(() -> service.ensureColumnRole(7, "user-1", ProjectRole.VIEWER))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Column with id \"7\" not found"));
    }

    @Test
    @DisplayName("resolves the project then enforces the role, returning the project id")
    void resolves() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "user-1"))
          .thenReturn(Optional.of(membership(ProjectRole.MEMBER)));
      assertThat(service.ensureColumnRole(7, "user-1", ProjectRole.MEMBER)).isEqualTo("proj1234");
    }

    @Test
    @DisplayName("still throws ForbiddenException for a member below the required role")
    void forbids() {
      when(queries.findProjectIdForColumn(7)).thenReturn(Optional.of("proj1234"));
      when(memberRepository.findByProjectIdAndUserId(any(), any()))
          .thenReturn(Optional.of(membership(ProjectRole.VIEWER)));
      assertThatThrownBy(() -> service.ensureColumnRole(7, "user-1", ProjectRole.MEMBER))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response((HttpException) e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least member role"));
    }
  }
}
```

- [ ] **Step 2: Run tests to verify they pass**

```bash
mvn test -Dtest='ProjectAccessServiceTest'
```

Expected: PASS (this is a verification pass, not red-green — the service already exists at `HEAD`).

- [ ] **Step 3: Verify the service**

`src/main/java/com/kanban/modules/project/ProjectAccessService.java`:

```java
package com.kanban.modules.project;

import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The single authorization gate for project-scoped resources.
 *
 * Convention: a caller with NO membership gets a 404 that is indistinguishable
 * from a nonexistent project (anti-enumeration). A member below the required
 * role gets a 403.
 */
@Service
public class ProjectAccessService {
  private final ProjectMemberRepository memberRepository;
  private final ProjectAccessQueries queries;

  public ProjectAccessService(ProjectMemberRepository memberRepository, ProjectAccessQueries queries) {
    this.memberRepository = memberRepository;
    this.queries = queries;
  }

  public ProjectMember getMembership(String projectId, String userId) {
    return memberRepository.findByProjectIdAndUserId(projectId, userId).orElse(null);
  }

  public ProjectMember ensureRole(String projectId, String userId, ProjectRole minimumRole) {
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Project with id \"" + projectId + "\" not found"));
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return membership;
  }

  public List<String> getProjectIdsForUser(String userId) {
    return memberRepository.findProjectIdsByUserId(userId);
  }

  public String getProjectIdForTask(String taskId) {
    return queries.findProjectIdForTask(taskId).orElseThrow(() -> taskNotFound(taskId));
  }

  public String getProjectIdForColumn(int columnId) {
    return queries.findProjectIdForColumn(columnId).orElseThrow(() -> columnNotFound(columnId));
  }

  /**
   * Inlined rather than delegated to ensureRole: a non-member must see a
   * task-flavored 404 (matching the unknown-task case byte-for-byte), never the
   * project-flavored message ensureRole throws.
   */
  public String ensureTaskRole(String taskId, String userId, ProjectRole minimumRole) {
    String projectId = getProjectIdForTask(taskId);
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw taskNotFound(taskId);
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return projectId;
  }

  public String ensureColumnRole(int columnId, String userId, ProjectRole minimumRole) {
    String projectId = getProjectIdForColumn(columnId);
    ProjectMember membership = getMembership(projectId, userId);
    if (membership == null) {
      throw columnNotFound(columnId);
    }
    if (membership.getRole().rank() < minimumRole.rank()) {
      throw forbidden(minimumRole);
    }
    return projectId;
  }

  private static ForbiddenException forbidden(ProjectRole minimumRole) {
    return new ForbiddenException(Json.map(
        "statusCode", 403,
        "message", "This action requires at least " + minimumRole.value() + " role"));
  }

  private static NotFoundException taskNotFound(String taskId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Task with id \"" + taskId + "\" not found"));
  }

  private static NotFoundException columnNotFound(int columnId) {
    return new NotFoundException(Json.map(
        "statusCode", 404,
        "message", "Column with id \"" + columnId + "\" not found"));
  }
}
```

Its two collaborators, also already present:

`src/main/java/com/kanban/modules/project/ProjectAccessQueries.java`:

```java
package com.kanban.modules.project;

import java.util.Optional;

/** The raw lookups ProjectAccessService runs through the DataSource query builder in Nest. */
public interface ProjectAccessQueries {
  /** {@code SELECT col.project_id FROM tasks task JOIN kanban_columns col ON col.id = task.column_id WHERE task.id = ?}. */
  Optional<String> findProjectIdForTask(String taskId);

  /** {@code SELECT col.project_id FROM kanban_columns col WHERE col.id = ?}. */
  Optional<String> findProjectIdForColumn(int columnId);
}
```

`JpaProjectAccessQueries` (`src/main/java/com/kanban/modules/project/JpaProjectAccessQueries.java`) implements it with two `EntityManager.createNativeQuery(...)` calls (a `@Repository` bean, one native query per method, no query builder needed) — see that file for the exact SQL. There is no `project.module.ts` to touch: `ProjectAccessService`, `ProjectAccessQueries`/`JpaProjectAccessQueries`, and `ProjectMemberRepository` are all Spring-managed beans (`@Service`/`@Repository`/`JpaRepository` interface) auto-detected by component scanning from `KanbanApplication`; nothing needs to be registered or exported by hand.

- [ ] **Step 4: Run tests to verify they pass**

```bash
mvn test -Dtest='ProjectAccessServiceTest' && mvn -q compile
```

Expected: PASS, build green.

- [ ] **Step 5: Commit**

This task is already satisfied by the code at `HEAD` — `ProjectAccessService.java` and its test shipped in the baseline commit (`feat(core): initial Spring Boot port of the Kanban NestJS backend`), not a separate task commit. If you find drift and need to fix it, commit with:

```bash
git add src/main/java/com/kanban/modules/project/ProjectAccessService.java src/test/java/com/kanban/modules/project/ProjectAccessServiceTest.java
git commit -m "feat(project): add ProjectAccessService central authorization gate

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---
## Task 3: Rewire existing checks through the gate

> ✔ Already implemented in this repo — verify, don't re-create.

**Files:**
- Verify: `src/main/java/com/kanban/modules/project/ProjectService.java` (constructor takes `ProjectAccessService`; no `ensureProjectRole` method; `addMembers`/`removeMembers` take a required `String actorId` and call `projectAccessService.ensureRole(...)`)
- Verify: `src/main/java/com/kanban/modules/project/ProjectController.java` (no signature change needed — it already passes `userId` from `@CurrentUser("id")`)
- Verify: `src/main/java/com/kanban/modules/team/TeamService.java` (constructor takes `ProjectAccessService`; no private `ensureProjectRole` method; `create`/`addMember`/`removeMember` take a required `actorId` and call `projectAccessService.ensureRole(...)`)

**Interfaces:**
- Consumes: `ProjectAccessService.ensureRole` (Task 2).
- Produces: `ProjectService.addMembers(String projectId, List<String> userIds, String actorId)` and `removeMembers(String projectId, List<String> userIds, String actorId)` — actor now **required** (a `String` parameter, not `Optional`/nullable — a missing actor is a compile error, not a skipped check). `TeamService.create/addMember/removeMember` likewise take a required `actorId`.

- [ ] **Step 1: ProjectService**

Confirm in `src/main/java/com/kanban/modules/project/ProjectService.java`:
1. The constructor takes `ProjectAccessService projectAccessService` as its sixth parameter — already wired.
2. There is no `ensureProjectRole` method anywhere in the class; it was never carried over from the NestJS original.
3. `addMembers` and `removeMembers` both declare `String actorId` (not optional) and call

```java
projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
```

directly — there is no `if (actorId != null)` wrapper to remove; the required parameter makes the guard unconditional by construction.

(`removeMembers` no longer calls `ensureProjectExists` before the role check either — Task 6 rewrote the whole method, and `ensureRole`'s 404 masking made the separate existence check redundant.)

- [ ] **Step 2: TeamService**

Confirm in `src/main/java/com/kanban/modules/team/TeamService.java`:
1. The constructor takes `ProjectAccessService projectAccessService` — Spring's component scan resolves this automatically; there is no NestJS-style `TeamModule` `imports: [ProjectModule]` step to perform.
2. There is no private `ensureProjectRole` method. `create`, `addMember`, and `removeMember` each call `projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN)` directly, with `actorId` a required parameter on all three (no `if (actorId)` wrapper anywhere).
3. `TeamController.java` already passes `userId`/`actorId` from `@CurrentUser("id")` on every mutating route — no controller changes needed.

- [ ] **Step 3: Verify no caller passes a null/absent actor**

```bash
grep -rn "addMembers\|removeMembers" src/main/java --include="*.java"
grep -rn "teamService\." src/main/java --include="*.java"
```

Expected: only `ProjectController`/`TeamController` call sites, all passing `userId`/`actorId` sourced from `@CurrentUser("id")` — confirmed against this repo's current `src/main/java` (the two `ProjectService` method declarations plus their two `ProjectController` call sites; the six `TeamController` → `teamService.*` call sites).

- [ ] **Step 4: Full test run (no commit — nothing to change)**

```bash
mvn -q compile && mvn test
```

Expected: green. This task's behavior was already folded into the initial Spring Boot port commit (`cffc49f feat(core): initial Spring Boot port of the Kanban NestJS backend`) — there is nothing new to stage or commit here.

---

## Task 4: `RequireProjectRole` annotation + `ProjectRoleInterceptor`

**Files:**
- Create: `src/main/java/com/kanban/modules/project/guards/RequireProjectRole.java`
- Create: `src/main/java/com/kanban/modules/project/guards/ProjectRoleInterceptor.java`
- Create: `src/test/java/com/kanban/modules/project/guards/ProjectRoleInterceptorTest.java`
- Modify: `src/main/java/com/kanban/config/WebMvcConfig.java` (register the interceptor)
- Modify: `src/test/java/com/kanban/WebLayerTest.java` (the shared `@WebMvcTest` slice now loads the global interceptor and must mock its dependency)

**Interfaces:**
- Consumes: `ProjectAccessService.ensureRole` (Task 2).
- Produces: `@RequireProjectRole(value = role, param = "projectId")` method annotation; `ProjectRoleInterceptor`, registered globally in `WebMvcConfig#addInterceptors` **after** `JwtAuthInterceptor` (order matters — JWT populates the request's authenticated user first). On success, attaches the membership to the request attribute `ProjectRoleInterceptor.MEMBERSHIP_ATTRIBUTE` (`"projectMembership"`). Translation note: NestJS's per-route `@UseGuards(JwtAuthGuard, ProjectRoleGuard)` pairing and its `PROJECT_ROLE_KEY` reflector metadata collapse here into plain `@JwtAuth` + `@RequireProjectRole` annotations on the handler method — both interceptors are registered exactly once, globally, in `WebMvcConfig`, and are gated per-route purely by whether the handler method carries the annotation. There is no per-module "provide + export the guard" step and no `ProjectModule` for callers to import.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/kanban/modules/project/guards/ProjectRoleInterceptorTest.java`:

```java
package com.kanban.modules.project.guards;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Port of project-role.guard.spec.ts. The {@code PROJECT_ROLE_KEY} metadata-key
 * test is skipped: in Java the {@code @RequireProjectRole} annotation itself is
 * the metadata, there is no separate reflector key to assert on.
 */
class ProjectRoleInterceptorTest {

  private ProjectAccessService projectAccessService;
  private ProjectRoleInterceptor interceptor;
  private MockHttpServletResponse response;

  /** Dummy handler methods carrying (or not carrying) the annotation under test. */
  private static class DummyController {
    @RequireProjectRole(ProjectRole.VIEWER)
    void withDefaultParam() {}

    @RequireProjectRole(value = ProjectRole.ADMIN, param = "id")
    void withCustomParam() {}

    void withoutAnnotation() {}
  }

  private static HandlerMethod handlerMethod(String name) throws NoSuchMethodException {
    Method method = DummyController.class.getDeclaredMethod(name);
    return new HandlerMethod(new DummyController(), method);
  }

  private static User userWithId(String id) {
    User user = new User();
    user.setId(id);
    return user;
  }

  @BeforeEach
  void setUp() {
    projectAccessService = mock(ProjectAccessService.class);
    interceptor = new ProjectRoleInterceptor(projectAccessService);
    response = new MockHttpServletResponse();
  }

  @Test
  @DisplayName("passes routes with no @RequireProjectRole metadata untouched")
  void passesUnannotatedRoutes() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();

    boolean result = interceptor.preHandle(request, response, handlerMethod("withoutAnnotation"));

    assertThat(result).isTrue();
    verifyNoInteractions(projectAccessService);
  }

  @Test
  @DisplayName("throws UnauthorizedException when request has no authenticated user")
  void throwsWhenUserMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "proj1234"));

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isInstanceOf(UnauthorizedException.class);
  }

  @Test
  @DisplayName("throws NotFoundException when the named path variable is missing")
  void throwsWhenPathVariableMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    // no URI_TEMPLATE_VARIABLES_ATTRIBUTE set at all.

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isInstanceOf(NotFoundException.class)
        .satisfies(e -> assertThat(((HttpException) e).getResponse())
            .isEqualTo(Map.of("statusCode", 404, "message", "Project not found")));
  }

  @Test
  @DisplayName("enforces the role from the named route param and attaches the membership")
  void enforcesRoleAndAttachesMembership() throws Exception {
    ProjectMember membership = new ProjectMember("proj1234", "user-1", ProjectRole.OWNER);
    when(projectAccessService.ensureRole("proj1234", "user-1", ProjectRole.ADMIN)).thenReturn(membership);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", "proj1234"));

    boolean result = interceptor.preHandle(request, response, handlerMethod("withCustomParam"));

    assertThat(result).isTrue();
    verify(projectAccessService).ensureRole("proj1234", "user-1", ProjectRole.ADMIN);
    assertThat(request.getAttribute("projectMembership")).isSameAs(membership);
  }

  @Test
  @DisplayName("propagates 404/403 from ProjectAccessService untouched")
  void propagatesAccessServiceErrors() throws Exception {
    RuntimeException boom = new RuntimeException("boom");
    when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(boom);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(JwtAuthInterceptor.USER_ATTRIBUTE, userWithId("user-1"));
    request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("projectId", "p"));

    assertThatThrownBy(() -> interceptor.preHandle(request, response, handlerMethod("withDefaultParam")))
        .isSameAs(boom);
  }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
mvn test -Dtest='ProjectRoleInterceptorTest'
```

Expected: FAIL — compilation error, `RequireProjectRole` and `ProjectRoleInterceptor` don't exist yet.

- [ ] **Step 3: Implement annotation and interceptor**

Create `src/main/java/com/kanban/modules/project/guards/RequireProjectRole.java`:

```java
package com.kanban.modules.project.guards;

import com.kanban.modules.project.ProjectRole;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the minimum project role required for a route. The project id is read
 * from the path variable named {@link #param()} (default {@code "projectId"};
 * pass {@code "id"} when the controller uses {@code /{id}}). Enforced by
 * {@link ProjectRoleInterceptor}, which must run after the JWT auth interceptor
 * so the authenticated user is already attached to the request.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireProjectRole {
  ProjectRole value();

  String param() default "projectId";
}
```

Create `src/main/java/com/kanban/modules/project/guards/ProjectRoleInterceptor.java`:

```java
package com.kanban.modules.project.guards;

import com.kanban.common.exception.NotFoundException;
import com.kanban.common.exception.UnauthorizedException;
import com.kanban.common.json.Json;
import com.kanban.modules.auth.guards.JwtAuthInterceptor;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.user.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Port of ProjectRoleGuard: enforces the minimum project role declared by
 * {@code @RequireProjectRole} on the handler method. Must be registered after
 * {@code JwtAuthInterceptor} in {@code WebMvcConfig} so the authenticated user is
 * already attached to the request.
 */
@Component
public class ProjectRoleInterceptor implements HandlerInterceptor {
  public static final String MEMBERSHIP_ATTRIBUTE = "projectMembership";

  private final ProjectAccessService projectAccessService;

  public ProjectRoleInterceptor(ProjectAccessService projectAccessService) {
    this.projectAccessService = projectAccessService;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    RequireProjectRole meta = method.getMethodAnnotation(RequireProjectRole.class);
    if (meta == null) {
      return true;
    }
    User user = (User) request.getAttribute(JwtAuthInterceptor.USER_ATTRIBUTE);
    if (user == null) {
      // JwtAuthInterceptor must be registered before this interceptor.
      throw new UnauthorizedException();
    }
    String projectId = pathVariable(request, meta.param());
    if (projectId == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "Project not found"));
    }
    request.setAttribute(
        MEMBERSHIP_ATTRIBUTE,
        projectAccessService.ensureRole(projectId, user.getId(), meta.value()));
    return true;
  }

  private static String pathVariable(HttpServletRequest request, String name) {
    Object vars = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (!(vars instanceof Map<?, ?> map)) {
      return null;
    }
    Object value = map.get(name);
    return value == null ? null : value.toString();
  }
}
```

In `WebMvcConfig.java`, inject `ProjectRoleInterceptor` alongside `JwtAuthInterceptor` and register it second (order matters):

```java
public class WebMvcConfig implements WebMvcConfigurer {
  private final ObjectMapper objectMapper;
  private final JwtAuthInterceptor jwtAuthInterceptor;
  private final ProjectRoleInterceptor projectRoleInterceptor;

  public WebMvcConfig(
      ObjectMapper objectMapper,
      JwtAuthInterceptor jwtAuthInterceptor,
      ProjectRoleInterceptor projectRoleInterceptor) {
    this.objectMapper = objectMapper;
    this.jwtAuthInterceptor = jwtAuthInterceptor;
    this.projectRoleInterceptor = projectRoleInterceptor;
  }

  // ...

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(jwtAuthInterceptor);
    registry.addInterceptor(projectRoleInterceptor);
  }
```

The shared `WebLayerTest` (the `@WebMvcTest` slice covering `AppController`, `AuthController`, `UserController`, `BoardController`) now loads this newly-registered global interceptor, so it must import `ProjectRoleInterceptor.class` and mock its `ProjectAccessService` dependency:

```java
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.guards.ProjectRoleInterceptor;
// ...

@WebMvcTest(controllers = {AppController.class, AuthController.class, UserController.class,
    BoardController.class})
@Import({WebMvcConfig.class, JacksonConfig.class, GlobalExceptionHandler.class, JwtAuthInterceptor.class,
    ProjectRoleInterceptor.class, AppService.class})
class WebLayerTest {
  // ...

  @MockitoBean
  private ProjectAccessService projectAccessService;
```

- [ ] **Step 4: Run tests, build, commit**

```bash
mvn test -Dtest='ProjectRoleInterceptorTest' && mvn -q compile
git add src/main/java/com/kanban/modules/project/guards src/main/java/com/kanban/config/WebMvcConfig.java src/test/java/com/kanban/modules/project/guards src/test/java/com/kanban/WebLayerTest.java
git commit -m "feat(project): add RequireProjectRole annotation and ProjectRoleInterceptor

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 5: Member role-change endpoint

**Files:**
- Create: `src/main/java/com/kanban/modules/project/dto/UpdateMemberRoleDto.java`
- Modify: `src/main/java/com/kanban/modules/project/ProjectMemberRepository.java` (add `findByProjectIdAndUserIdWithUser`, `countByProjectIdAndRole`)
- Modify: `src/main/java/com/kanban/modules/project/ProjectService.java` (add `changeMemberRole` + `ensureNotLastOwner`)
- Modify: `src/main/java/com/kanban/modules/project/ProjectController.java` (add `PATCH /{id}/members/{userId}`)
- Create: `src/test/java/com/kanban/modules/project/ProjectServiceTest.java` (new test class — the service had none before this task)

**Interfaces:**
- Consumes: `ProjectAccessService.ensureRole` (returns actor membership), `ProjectRole.rank()` (this port's `PROJECT_ROLE_HIERARCHY`).
- Produces: `ProjectService.changeMemberRole(String projectId, String targetUserId, ProjectRole newRole, String actorId): ProjectMember`; `private void ensureNotLastOwner(String projectId, List<String> leavingOwnerIds)` (throws 409) — Task 6 reuses `ensureNotLastOwner`.

Policy (from the canonical model): base gate `admin`; touching `owner`/`admin` in either direction requires `owner`; no self-change (403); demoting the last owner → 409; same-role change is a no-op returning the membership.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/kanban/modules/project/ProjectServiceTest.java`:

```java
package com.kanban.modules.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.team.TeamMemberRepository;
import com.kanban.modules.user.UserRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/** Port of project.service.spec.ts — member management. */
class ProjectServiceTest {
  private ProjectRepository projectRepository;
  private ProjectMemberRepository memberRepository;
  private UserRepository userRepository;
  private TeamMemberRepository teamMemberRepository;
  private TransactionTemplate transactionTemplate;
  private ProjectAccessService projectAccessService;
  private ProjectService service;

  private static ProjectMember member(String userId, ProjectRole role) {
    return new ProjectMember("proj1234", userId, role);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  @BeforeEach
  void setUp() {
    projectRepository = mock(ProjectRepository.class);
    memberRepository = mock(ProjectMemberRepository.class);
    userRepository = mock(UserRepository.class);
    teamMemberRepository = mock(TeamMemberRepository.class);
    transactionTemplate = mock(TransactionTemplate.class);
    projectAccessService = mock(ProjectAccessService.class);
    service = new ProjectService(projectRepository, memberRepository, userRepository, teamMemberRepository,
        transactionTemplate, projectAccessService);
  }

  @Nested
  class ChangeMemberRole {
    @Test
    @DisplayName("rejects changing your own role")
    void rejectsSelfChange() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "actor", ProjectRole.ADMIN, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "You cannot change your own role"));
    }

    @Test
    @DisplayName("404s when the target is not a member")
    void notFoundWhenTargetNotMember() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "ghost")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "ghost", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "User is not a member of this project"));
    }

    @Test
    @DisplayName("lets an admin toggle member <-> viewer")
    void adminTogglesMemberViewer() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.VIEWER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.VIEWER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.VIEWER);
      verify(memberRepository).save(any(ProjectMember.class));
    }

    @Test
    @DisplayName("blocks an admin from promoting to admin (owner-only)")
    void blocksAdminPromotingToAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.ADMIN, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
      verify(memberRepository, never()).save(any());
    }

    @Test
    @DisplayName("blocks an admin from demoting an owner")
    void blocksAdminDemotingOwner() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
    }

    @Test
    @DisplayName("409s when demoting the last owner")
    void conflictsOnLastOwnerDemotion() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));
      when(memberRepository.countByProjectIdAndRole("proj1234", ProjectRole.OWNER)).thenReturn(1L);

      assertThatThrownBy(() -> service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A project must have at least one owner"));
    }

    @Test
    @DisplayName("lets an owner promote another member to owner")
    void ownerPromotesMemberToOwner() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.OWNER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.OWNER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.OWNER);
    }

    @Test
    @DisplayName("same-role change is a no-op that still returns the membership")
    void sameRoleIsNoOp() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.ADMIN))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserId("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));
      when(memberRepository.findByProjectIdAndUserIdWithUser("proj1234", "target"))
          .thenReturn(Optional.of(member("target", ProjectRole.MEMBER)));

      ProjectMember result = service.changeMemberRole("proj1234", "target", ProjectRole.MEMBER, "actor");

      assertThat(result.getRole()).isEqualTo(ProjectRole.MEMBER);
      verify(memberRepository, never()).save(any());
    }
  }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
mvn test -Dtest='ProjectServiceTest'
```

Expected: FAIL — compilation error, `changeMemberRole` is not a method on `ProjectService`.

- [ ] **Step 3: Implement DTO, repository additions, service method, route**

Create `src/main/java/com/kanban/modules/project/dto/UpdateMemberRoleDto.java`:

```java
package com.kanban.modules.project.dto;

import com.kanban.common.validation.IsEnum;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.project.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;

public class UpdateMemberRoleDto extends ValidatedDto {
  @Schema(example = "admin", description = "New role for the member")
  @IsEnum(ProjectRole.class)
  public ProjectRole role;
}
```

Add to `ProjectMemberRepository`:

```java
@EntityGraph(attributePaths = "user")
@Query("select m from ProjectMember m where m.projectId = :projectId and m.userId = :userId")
Optional<ProjectMember> findByProjectIdAndUserIdWithUser(@Param("projectId") String projectId,
    @Param("userId") String userId);

long countByProjectIdAndRole(String projectId, ProjectRole role);
```

Add to `ProjectService` (after `removeMembers`) — note: no try/catch, throw directly:

```java
  /**
   * Change a member's role. Base gate: admin+. Touching owner/admin in either
   * direction (current role or new role) is owner-only. Self-change is always
   * rejected. Demoting the last owner is rejected. A same-role change is a no-op.
   */
  public ProjectMember changeMemberRole(String projectId, String targetUserId, ProjectRole newRole, String actorId) {
    ProjectMember actor = projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);

    if (actorId.equals(targetUserId)) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "You cannot change your own role"));
    }

    ProjectMember target = memberRepository.findByProjectIdAndUserId(projectId, targetUserId).orElse(null);
    if (target == null) {
      throw new NotFoundException(Json.map(
          "statusCode", 404,
          "message", "User is not a member of this project"));
    }

    // Touching owner/admin roles in either direction is owner-only.
    boolean touchesElevatedRole = isElevated(target.getRole()) || isElevated(newRole);
    ProjectRole requiredRole = touchesElevatedRole ? ProjectRole.OWNER : ProjectRole.ADMIN;
    if (actor.getRole().rank() < requiredRole.rank()) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "This action requires at least " + requiredRole.value() + " role"));
    }

    if (target.getRole() == ProjectRole.OWNER && newRole != ProjectRole.OWNER) {
      ensureNotLastOwner(projectId, List.of(targetUserId));
    }

    if (target.getRole() != newRole) {
      target.setRole(newRole);
      memberRepository.save(target);
    }

    return memberRepository.findByProjectIdAndUserIdWithUser(projectId, targetUserId).orElse(target);
  }

  private static boolean isElevated(ProjectRole role) {
    return role == ProjectRole.OWNER || role == ProjectRole.ADMIN;
  }

  private void ensureNotLastOwner(String projectId, List<String> leavingOwnerIds) {
    long ownersCount = memberRepository.countByProjectIdAndRole(projectId, ProjectRole.OWNER);
    if (ownersCount - leavingOwnerIds.size() < 1) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "A project must have at least one owner"));
    }
  }
```

Add to `ProjectController` (after `removeMembers`):

```java
  @PatchMapping("/{id}/members/{userId}")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Change a member's role (admin+; owner for owner/admin changes)")
  @Parameter(name = "id", description = "Project ID")
  @Parameter(name = "userId", description = "User UUID")
  @ApiResponse(responseCode = "200", description = "Member role updated")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project or member not found")
  @ApiResponse(responseCode = "409", description = "Project must keep at least one owner")
  public Map<String, Object> changeMemberRole(@Param(value = "id", pipe = Param.Pipe.PROJECT_ID) String id,
      @Param(value = "userId", pipe = Param.Pipe.UUID) String userId,
      @ValidatedBody UpdateMemberRoleDto dto, @CurrentUser("id") String actorId) {
    return projectService.changeMemberRole(id, userId, dto.role, actorId).toJsonWithUser();
  }
```

- [ ] **Step 4: Run tests, build, commit**

```bash
mvn test -Dtest='ProjectServiceTest' && mvn -q compile
git add src/main/java/com/kanban/modules/project src/test/java/com/kanban/modules/project/ProjectServiceTest.java
git commit -m "feat(project): add member role-change endpoint with last-owner protection

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 6: Member removal protections + self-leave

**Files:**
- Modify: `src/main/java/com/kanban/modules/project/ProjectService.java` (rewrite `removeMembers`)
- Modify: `src/test/java/com/kanban/modules/project/ProjectServiceTest.java` (add `RemoveMembers` nested class)
- Modify: `src/main/java/com/kanban/modules/project/ProjectController.java` (Swagger response only)

**Interfaces:**
- Consumes: `ensureNotLastOwner` (Task 5), `ProjectAccessService.ensureRole`.
- Produces: `removeMembers(String projectId, List<String> userIds, String actorId): void` with: self-leave allowed at any role; removing member/viewer requires `admin`; removing admin/owner requires `owner`; last-owner protected; `team_members` cleanup and member deletion in one `@Transactional` service method — this port's translation of the plan's `dataSource.transaction(...)`, per constraints.md.

- [ ] **Step 1: Add failing tests to `ProjectServiceTest.java`**

```java
  @Nested
  class RemoveMembers {
    @Test
    @DisplayName("allows a viewer to remove themselves (self-leave)")
    void allowsSelfLeave() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.VIEWER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("actor")))
          .thenReturn(List.of(member("actor", ProjectRole.VIEWER)));

      assertThatCode(() -> service.removeMembers("proj1234", List.of("actor"), "actor"))
          .doesNotThrowAnyException();

      verify(teamMemberRepository).deleteByProjectIdAndUserIdIn("proj1234", List.of("actor"));
      verify(memberRepository).deleteByProjectIdAndUserIdIn("proj1234", List.of("actor"));
    }

    @Test
    @DisplayName("blocks a member from removing someone else")
    void blocksMemberRemovingSomeoneElse() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.MEMBER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.MEMBER)));

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least admin role"));
    }

    @Test
    @DisplayName("blocks an admin from removing another admin (owner-only)")
    void blocksAdminRemovingAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.ADMIN));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.ADMIN)));

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .isInstanceOf(ForbiddenException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 403)
              .containsEntry("message", "This action requires at least owner role"));
    }

    @Test
    @DisplayName("409s when removal would leave zero owners (including self-leave)")
    void conflictsOnLastOwnerRemoval() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("actor")))
          .thenReturn(List.of(member("actor", ProjectRole.OWNER)));
      when(memberRepository.countByProjectIdAndRole("proj1234", ProjectRole.OWNER)).thenReturn(1L);

      assertThatThrownBy(() -> service.removeMembers("proj1234", List.of("actor"), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A project must have at least one owner"));
    }

    @Test
    @DisplayName("lets an owner remove an admin")
    void ownerRemovesAdmin() {
      when(projectAccessService.ensureRole("proj1234", "actor", ProjectRole.VIEWER))
          .thenReturn(member("actor", ProjectRole.OWNER));
      when(memberRepository.findByProjectIdAndUserIdIn("proj1234", List.of("victim")))
          .thenReturn(List.of(member("victim", ProjectRole.ADMIN)));

      assertThatCode(() -> service.removeMembers("proj1234", List.of("victim"), "actor"))
          .doesNotThrowAnyException();
    }
  }
```

(Needs `import static org.assertj.core.api.Assertions.assertThatCode;` and `import java.util.List;` added to the test class's imports.)

- [ ] **Step 2: Run to verify the new cases fail**

```bash
mvn test -Dtest='ProjectServiceTest'
```

Expected: the new `RemoveMembers` cases FAIL against the old implementation (it required admin even for self-leave, had no owner protections, and didn't reject demoting the last owner).

- [ ] **Step 3: Rewrite `removeMembers`**

Replace the whole method in `ProjectService.java` (drop the try/catch and the now-redundant `ensureProjectExists` call — `ensureRole` already 404-masks a nonexistent project; annotate the method `@Transactional` — this port's translation of the plan's `dataSource.transaction(...)`, per constraints.md):

```java
  /**
   * Remove members from a project. Base gate: viewer+ (any member may self-leave).
   * Self-leave (exactly one target id, equal to the actor) is allowed at any
   * role. Otherwise, removing an owner/admin requires owner; removing a
   * member/viewer requires admin. Removing the last owner is rejected.
   */
  @Transactional
  public void removeMembers(String projectId, List<String> userIds, String actorId) {
    ProjectMember actor = projectAccessService.ensureRole(projectId, actorId, ProjectRole.VIEWER);

    boolean isSelfLeave = userIds.size() == 1 && userIds.get(0).equals(actorId);
    List<ProjectMember> targets = memberRepository.findByProjectIdAndUserIdIn(projectId, userIds);

    if (!isSelfLeave) {
      boolean touchesElevatedRole = targets.stream().anyMatch(t -> isElevated(t.getRole()));
      ProjectRole requiredRole = touchesElevatedRole ? ProjectRole.OWNER : ProjectRole.ADMIN;
      if (actor.getRole().rank() < requiredRole.rank()) {
        throw new ForbiddenException(Json.map(
            "statusCode", 403,
            "message", "This action requires at least " + requiredRole.value() + " role"));
      }
    }

    List<String> leavingOwnerIds = targets.stream()
        .filter(t -> t.getRole() == ProjectRole.OWNER)
        .map(ProjectMember::getUserId)
        .toList();
    if (!leavingOwnerIds.isEmpty()) {
      ensureNotLastOwner(projectId, leavingOwnerIds);
    }

    // A user leaving the project also leaves any team in it.
    teamMemberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds);
    memberRepository.deleteByProjectIdAndUserIdIn(projectId, userIds);
  }
```

Update the `DELETE /{id}/members` route's Swagger in the controller: add

```java
@ApiResponse(responseCode = "409", description = "Project must keep at least one owner")
```

- [ ] **Step 4: Run tests, build, commit**

```bash
mvn test -Dtest='ProjectServiceTest' && mvn -q compile
git add src/main/java/com/kanban/modules/project src/test/java/com/kanban/modules/project/ProjectServiceTest.java
git commit -m "feat(project): removal protections, self-leave, last-owner guard

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---
## Task 7: `ProjectInvitation` entity, migration, module scaffold

**Files:**
- Create: `src/main/java/com/kanban/modules/invitation/ProjectInvitation.java`
- Create: `src/main/java/com/kanban/modules/invitation/ProjectInvitationRepository.java`
- Create: `src/main/resources/db/migration/V2__create_project_invitations.sql`
- No Java equivalent: `invitation.module.ts` / the `app.module.ts` import. Spring component-scans `@Entity`, `@Repository`-eligible `JpaRepository` interfaces, `@Service`, and `@RestController` classes under `com.kanban` automatically — there is no module-registration file to create or wire up. The scaffold this task produces is the entity plus a bare repository interface.

**Interfaces:**
- Produces: `ProjectInvitation` entity (fields below) + `ProjectInvitationRepository` (initially a marker interface with no custom finders). Tasks 8–9 build the service/controller and add finder methods to the repository as the service needs them.

- [ ] **Step 1: Create the entity**

`src/main/java/com/kanban/modules/invitation/ProjectInvitation.java`:

```java
package com.kanban.modules.invitation;

import com.kanban.common.json.Json;
import com.kanban.modules.project.Project;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.project.ProjectRoleConverter;
import com.kanban.modules.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "project_invitations")
public class ProjectInvitation {
  @Id
  @UuidGenerator
  @Column(columnDefinition = "uuid")
  private String id;

  @Column(name = "project_id", nullable = false, length = 8)
  private String projectId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "project_id", insertable = false, updatable = false)
  private Project project;

  @Column(nullable = false, length = 255)
  private String email;

  @Convert(converter = ProjectRoleConverter.class)
  @Column(nullable = false, columnDefinition = "project_role")
  private ProjectRole role = ProjectRole.MEMBER;

  /** Never serialized ({@code select: false} in TypeORM); loaded only to verify a token. */
  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "invited_by", columnDefinition = "uuid")
  private String invitedBy;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "invited_by", insertable = false, updatable = false)
  private User inviter;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "accepted_at")
  private Instant acceptedAt;

  @Column(name = "accepted_by", columnDefinition = "uuid")
  private String acceptedBy;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @CreationTimestamp(source = SourceType.DB)
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  public ProjectInvitation() {}

  /**
   * TypeORM {@code toJSON()}: drops token_hash, invited_by and accepted_by. The
   * inviter relation is included only when it was loaded ({@code withInviter}).
   */
  public Map<String, Object> toJson(boolean withInviter) {
    Map<String, Object> json = Json.map(
        "id", id,
        "project_id", projectId,
        "email", email,
        "role", role,
        "expires_at", expiresAt,
        "accepted_at", acceptedAt,
        "revoked_at", revokedAt,
        "created_at", createdAt);
    if (withInviter) {
      json.put("inviter", inviter == null ? null : inviter.toJson());
    }
    return json;
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public Project getProject() { return project; }
  public void setProject(Project project) { this.project = project; }
  public String getEmail() { return email; }
  public void setEmail(String email) { this.email = email; }
  public ProjectRole getRole() { return role; }
  public void setRole(ProjectRole role) { this.role = role; }
  public String getTokenHash() { return tokenHash; }
  public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
  public String getInvitedBy() { return invitedBy; }
  public void setInvitedBy(String invitedBy) { this.invitedBy = invitedBy; }
  public User getInviter() { return inviter; }
  public void setInviter(User inviter) { this.inviter = inviter; }
  public Instant getExpiresAt() { return expiresAt; }
  public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
  public Instant getAcceptedAt() { return acceptedAt; }
  public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
  public String getAcceptedBy() { return acceptedBy; }
  public void setAcceptedBy(String acceptedBy) { this.acceptedBy = acceptedBy; }
  public Instant getRevokedAt() { return revokedAt; }
  public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

There is no Swagger `@ApiHideProperty`/`@ApiProperty` field-by-field annotation here, and no TypeORM `select: false` + destructuring `toJSON()`. This port's convention (see every other entity) is: the JPA entity carries no wire-format annotations at all, and the controller layer always calls an explicit `toJson(...)` allowlist method before returning an entity to Jackson. `ProjectInvitation.toJson(boolean withInviter)` is that allowlist — `token_hash`, `invited_by`, and `accepted_by` are simply never put into the map, so they can never leak regardless of what the repository loaded. `withInviter` toggles whether the (separately loaded) `inviter` relation is nested in the JSON — `true` for the single-invitation views (`findPending`, `accept`'s returned project), `false` for `create`'s response (which never loads the relation).

- [ ] **Step 2: Create the repository (module scaffold)**

Unlike NestJS, there is no `invitation.module.ts` to write and no `app.module.ts` import to add — Spring's component scan already covers `com.kanban.modules.invitation`. The equivalent of "start with the entity registration so this commit compiles" is a bare repository interface with no custom finders yet; Task 8 adds `findByIdAndProjectId`/`findPendingByProjectIdAndEmail`/`findPendingByProjectId`, and Task 9 adds `findByTokenHash`.

`src/main/java/com/kanban/modules/invitation/ProjectInvitationRepository.java`:

```java
package com.kanban.modules.invitation;

import org.springframework.data.jpa.repository.JpaRepository;

/** Custom finders are added in Task 8 as the service needs them. */
public interface ProjectInvitationRepository extends JpaRepository<ProjectInvitation, String> {
}
```

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V2__create_project_invitations.sql`:

```sql
-- ---------------------------------------------------------------------------
-- project_invitations
-- ---------------------------------------------------------------------------
CREATE TABLE project_invitations (
  id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  project_id  VARCHAR(8)   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  email       VARCHAR(255) NOT NULL,
  role        project_role NOT NULL DEFAULT 'member',
  token_hash  VARCHAR(64)  NOT NULL,
  invited_by  UUID         REFERENCES users(id) ON DELETE SET NULL,
  expires_at  TIMESTAMPTZ  NOT NULL,
  accepted_at TIMESTAMPTZ,
  accepted_by UUID,
  revoked_at  TIMESTAMPTZ,
  created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_project_invitations_token_hash ON project_invitations (token_hash);
CREATE INDEX idx_project_invitations_project_id ON project_invitations (project_id);
CREATE INDEX idx_project_invitations_email ON project_invitations (email);
```

Flyway numbers this `V2__*` — `V1` is this repo's baseline schema migration (`project_role` already contains `'viewer'` there; no enum changes needed for the invitation's `role` column).

- [ ] **Step 4: Build, commit**

```bash
mvn -q compile
git add src/main/java/com/kanban/modules/invitation src/main/resources/db/migration/V2__create_project_invitations.sql
git commit -m "feat(invitation): add ProjectInvitation entity, migration, module scaffold

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 8: Invitation create / list / revoke

**Files:**
- Create: `src/main/java/com/kanban/modules/invitation/dto/CreateInvitationDto.java`
- Create: `src/main/java/com/kanban/modules/invitation/InvitationService.java`
- Create: `src/main/java/com/kanban/modules/invitation/InvitationController.java`
- Create: `src/test/java/com/kanban/modules/invitation/InvitationServiceTest.java`
- Modify: `src/main/java/com/kanban/modules/invitation/ProjectInvitationRepository.java` (add `findByIdAndProjectId`, `findPendingByProjectIdAndEmail`, `findPendingByProjectId`)
- Create: `src/main/java/com/kanban/common/validation/IsIn.java` + modify `src/main/java/com/kanban/common/validation/ClassValidator.java` (new constraint annotation — see Step 3; class-validator ships `@IsIn` for free, this port does not yet have an equivalent)
- Modify: `src/test/java/com/kanban/common/validation/ClassValidatorTest.java` (cover the new `@IsIn` branch — see Step 5; this has no counterpart in the original plan)

**Interfaces:**
- Consumes: `ProjectAccessService.ensureRole`/`getMembership`, `ProjectService.findOneById` (Spring beans injected by constructor — there is no module import to add).
- Produces:
  - `InvitationService.create(String projectId, CreateInvitationDto dto, String actorId): Map<String, Object>` — returns `saved.toJson(false)` with `token` added; **`token` appears only in this response**.
  - `InvitationService.findPending(String projectId, String actorId): ApiListResponse<Map<String, Object>>`
  - `InvitationService.revoke(String projectId, String invitationId, String actorId): void` (idempotent for already-revoked)
  - `private static String hashToken(String token)` — sha256 hex.
  - Task 9 adds `accept(...)` to this same service and the notification emit inside `create`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/kanban/modules/invitation/InvitationServiceTest.java` (JUnit 5 + Mockito + AssertJ; `@Nested` classes group `Create`/`Revoke` like the spec's `describe` blocks):

```java
package com.kanban.modules.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.HttpException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Port of invitation.service.spec.ts (Task 8: create / findPending / revoke). */
class InvitationServiceTest {
  private ProjectInvitationRepository invitationRepository;
  private UserRepository userRepository;
  private ProjectAccessService projectAccessService;
  private InvitationService service;

  private static CreateInvitationDto dto(String email, ProjectRole role) {
    CreateInvitationDto d = new CreateInvitationDto();
    d.email = email;
    d.role = role;
    if (role != null) {
      d.with("role");
    }
    return d;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> response(Throwable e) {
    return (Map<String, Object>) ((HttpException) e).getResponse();
  }

  private static String sha256(String raw) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @BeforeEach
  void setUp() {
    invitationRepository = mock(ProjectInvitationRepository.class);
    userRepository = mock(UserRepository.class);
    projectAccessService = mock(ProjectAccessService.class);
    service = new InvitationService(invitationRepository, userRepository, projectAccessService);

    when(invitationRepository.saveAndFlush(any(ProjectInvitation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Nested
  class Create {
    @Test
    @DisplayName("requires owner role to invite an admin")
    void requiresOwnerRoleToInviteAdmin() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.OWNER));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", ProjectRole.ADMIN), "actor");

      verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.OWNER);
    }

    @Test
    @DisplayName("requires only admin role to invite a member")
    void requiresOnlyAdminRoleToInviteMember() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", null), "actor");

      verify(projectAccessService).ensureRole("proj1234", "actor", ProjectRole.ADMIN);
    }

    @Test
    @DisplayName("409s when the invitee is already a member")
    void conflictsWhenInviteeAlreadyMember() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      User invitee = new User("invitee", "a@b.com", "Invitee", null, null, true);
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(invitee));
      when(projectAccessService.getMembership("proj1234", "invitee"))
          .thenReturn(new ProjectMember("proj1234", "invitee", ProjectRole.MEMBER));

      assertThatThrownBy(() -> service.create("proj1234", dto("a@b.com", null), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "User is already a member of this project"));
    }

    @Test
    @DisplayName("409s when a pending invitation already exists for the email")
    void conflictsWhenPendingInvitationExists() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.of(new ProjectInvitation()));

      assertThatThrownBy(() -> service.create("proj1234", dto("a@b.com", null), "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "A pending invitation already exists for this email"));
    }

    @Test
    @DisplayName("stores only the sha256 hash and returns the raw token once")
    void storesHashAndReturnsRawTokenOnce() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      Map<String, Object> result = service.create("proj1234", dto("A@B.com ", null), "actor");

      String token = (String) result.get("token");
      assertThat(token).hasSize(64);

      ArgumentCaptor<ProjectInvitation> captor = ArgumentCaptor.forClass(ProjectInvitation.class);
      verify(invitationRepository, times(1)).saveAndFlush(captor.capture());
      ProjectInvitation created = captor.getValue();

      assertThat(created.getTokenHash()).isEqualTo(sha256(token));
      assertThat(created.getEmail()).isEqualTo("a@b.com");
      assertThat(created.getTokenHash()).isNotEqualTo(token);
    }
  }

  @Nested
  class Revoke {
    @Test
    @DisplayName("404s for an unknown invitation in the project")
    void notFoundForUnknownInvitation() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.revoke("proj1234", "inv-uuid", "actor"))
          .isInstanceOf(NotFoundException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 404)
              .containsEntry("message", "Invitation not found"));
    }

    @Test
    @DisplayName("409s when the invitation was already accepted")
    void conflictsWhenAlreadyAccepted() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      ProjectInvitation invitation = new ProjectInvitation();
      invitation.setAcceptedAt(Instant.now());
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.revoke("proj1234", "inv-uuid", "actor"))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "Invitation has already been accepted"));
    }

    @Test
    @DisplayName("sets revoked_at and is idempotent")
    void setsRevokedAtAndIsIdempotent() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      ProjectInvitation invitation = new ProjectInvitation();
      when(invitationRepository.findByIdAndProjectId("inv-uuid", "proj1234")).thenReturn(Optional.of(invitation));

      service.revoke("proj1234", "inv-uuid", "actor");

      assertThat(invitation.getRevokedAt()).isNotNull();
      verify(invitationRepository, times(1)).save(invitation);

      // second call: already revoked -> no error, no extra save
      service.revoke("proj1234", "inv-uuid", "actor");
      verify(invitationRepository, times(1)).save(invitation);
    }
  }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
mvn test -Dtest='InvitationServiceTest'
```

Expected: FAIL — compilation error, `InvitationService` does not exist yet (there is no runtime "cannot find module" phase in Java; the test class fails to compile).

- [ ] **Step 3: Add the `@IsIn` validation annotation**

class-validator's `@IsIn([...])` restricts a field to an explicit allow-list; `common/validation` has `@IsEnum` (which accepts *every* value of an enum — `owner` included) but nothing that rejects a subset. `CreateInvitationDto.role` needs `owner` rejected at the validation layer, so this annotation has to be added, in the same shape as every other constraint in that package (annotation class + a branch in `ClassValidator.check()` + an entry in the `CONSTRAINTS` list scanned per field).

`src/main/java/com/kanban/common/validation/IsIn.java`:

```java
package com.kanban.common.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * class-validator {@code @IsIn(values)} equivalent — restricts a string-backed
 * (including wire-string-backed enum) field to an explicit allow-list, unlike
 * {@link IsEnum} which accepts every value of the enum.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface IsIn {
  String[] value();
}
```

In `ClassValidator.java`, add a branch next to the existing `IsEnum` handling and register the new annotation in `CONSTRAINTS`:

```java
    if (a instanceof IsIn isIn) {
      List<String> values = Arrays.asList(isIn.value());
      boolean ok = value instanceof String s && values.contains(s);
      return ok ? null : prop + " must be one of the following values: " + String.join(", ", values);
    }
```

```java
  private static final List<Class<? extends Annotation>> CONSTRAINTS = Arrays.asList(
      IsString.class, IsEmail.class, IsBoolean.class, IsDate.class, IsArray.class, IsNotEmpty.class,
      ArrayNotEmpty.class, ArrayMinSize.class, ArrayMaxSize.class, MinLength.class, MaxLength.class,
      Matches.class, Min.class, Max.class, IsInt.class, IsUUID.class, IsEnum.class, IsIn.class);
```

- [ ] **Step 4: Implement DTO, service, controller**

`src/main/java/com/kanban/modules/invitation/dto/CreateInvitationDto.java`:

```java
package com.kanban.modules.invitation.dto;

import com.kanban.common.validation.IsEmail;
import com.kanban.common.validation.IsIn;
import com.kanban.common.validation.IsOptional;
import com.kanban.common.validation.ValidatedDto;
import com.kanban.modules.project.ProjectRole;
import io.swagger.v3.oas.annotations.media.Schema;

public class CreateInvitationDto extends ValidatedDto {
  @Schema(example = "jane@example.com")
  @IsEmail
  public String email;

  @Schema(
      example = "member",
      allowableValues = {"admin", "member", "viewer"},
      description = "Role granted on acceptance. Owner cannot be granted by invitation.")
  @IsOptional
  @IsIn({"admin", "member", "viewer"})
  public ProjectRole role;
}
```

Unlike the plan's DTO, there is no `@Transform` trim/lowercase step here — this port's email normalization happens once, in the service (`dto.email.trim().toLowerCase(Locale.ROOT)`), matching what the plan's service does *in addition to* its DTO-level `@Transform`. One normalization site is enough; the DTO stays a plain `@IsEmail` field.

`src/main/java/com/kanban/modules/invitation/InvitationService.java` (the `accept` method, and the constructor's `ProjectMemberRepository`/`ProjectService`/`EventBus` dependencies, are added in Task 9 — nothing in `create`/`findPending`/`revoke` needs them yet):

```java
package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.exception.ConflictException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.common.json.Json;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import com.kanban.modules.user.User;
import com.kanban.modules.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Task 8: create / findPending / revoke. Task 9 adds {@code accept(...)} to
 * this same service (and, with it, {@code ProjectService}, {@code DataSource}
 * and the notification event emitter as constructor dependencies) — not
 * injected here since nothing in this task's three methods uses them.
 */
@Service
public class InvitationService {
  private static final Duration INVITATION_TTL = Duration.ofDays(7);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final ProjectInvitationRepository invitationRepository;
  private final UserRepository userRepository;
  private final ProjectAccessService projectAccessService;

  public InvitationService(ProjectInvitationRepository invitationRepository, UserRepository userRepository,
      ProjectAccessService projectAccessService) {
    this.invitationRepository = invitationRepository;
    this.userRepository = userRepository;
    this.projectAccessService = projectAccessService;
  }

  /** {@code randomBytes(32).toString('hex')} */
  private static String generateToken() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String hashToken(String token) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public Map<String, Object> create(String projectId, CreateInvitationDto dto, String actorId) {
    ProjectRole role = dto.role != null ? dto.role : ProjectRole.MEMBER;
    // Inviting an admin is owner-only; member/viewer invites are admin+.
    ProjectRole requiredRole = role == ProjectRole.ADMIN ? ProjectRole.OWNER : ProjectRole.ADMIN;
    projectAccessService.ensureRole(projectId, actorId, requiredRole);

    String email = dto.email.trim().toLowerCase(Locale.ROOT);

    User invitee = userRepository.findByEmail(email).orElse(null);
    if (invitee != null && projectAccessService.getMembership(projectId, invitee.getId()) != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "User is already a member of this project"));
    }

    if (invitationRepository.findPendingByProjectIdAndEmail(projectId, email, Instant.now()).isPresent()) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "A pending invitation already exists for this email"));
    }

    String token = generateToken();
    ProjectInvitation invitation = new ProjectInvitation();
    invitation.setProjectId(projectId);
    invitation.setEmail(email);
    invitation.setRole(role);
    invitation.setInvitedBy(actorId);
    invitation.setTokenHash(hashToken(token));
    invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
    ProjectInvitation saved = invitationRepository.saveAndFlush(invitation);

    // Task 9 adds: in-app notification when the invitee already has an account.

    // The raw token is returned exactly once; only its hash is stored.
    Map<String, Object> json = saved.toJson(false);
    json.put("token", token);
    return json;
  }

  public ApiListResponse<Map<String, Object>> findPending(String projectId, String actorId) {
    projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
    List<ProjectInvitation> invitations = invitationRepository.findPendingByProjectId(projectId, Instant.now());
    return ApiListResponse.ok(invitations.stream().map(i -> i.toJson(true)).toList());
  }

  public void revoke(String projectId, String invitationId, String actorId) {
    projectAccessService.ensureRole(projectId, actorId, ProjectRole.ADMIN);
    ProjectInvitation invitation = invitationRepository.findByIdAndProjectId(invitationId, projectId)
        .orElseThrow(() -> new NotFoundException(Json.map(
            "statusCode", 404,
            "message", "Invitation not found")));
    if (invitation.getAcceptedAt() != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "Invitation has already been accepted"));
    }
    if (invitation.getRevokedAt() != null) {
      return; // idempotent
    }
    invitation.setRevokedAt(Instant.now());
    invitationRepository.save(invitation);
  }
}
```

`src/main/java/com/kanban/modules/invitation/InvitationController.java` (the `accept` route is added in Task 9 — see that task's note on why the class-level `@RequestMapping` used here does not survive):

```java
package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project Invitations")
@RestController
@RequestMapping("/projects/{projectId}/invitations")
public class InvitationController {
  private final InvitationService invitationService;

  public InvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create an invitation (admin+; owner to invite as admin). "
      + "The raw token is returned only in this response.")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "201", description = "Invitation created (includes one-time token)")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  @ApiResponse(responseCode = "409", description = "Already a member or already invited")
  public Map<String, Object> create(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @ValidatedBody CreateInvitationDto dto, @CurrentUser("id") String actorId) {
    return invitationService.create(projectId, dto, actorId);
  }

  @GetMapping
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List pending invitations (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "List of pending invitations")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public ApiListResponse<Map<String, Object>> findPending(
      @Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @CurrentUser("id") String actorId) {
    return invitationService.findPending(projectId, actorId);
  }

  @DeleteMapping("/{invitationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Revoke a pending invitation (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "invitationId", description = "Invitation UUID")
  @ApiResponse(responseCode = "204", description = "Invitation revoked")
  @ApiResponse(responseCode = "404", description = "Project or invitation not found")
  @ApiResponse(responseCode = "409", description = "Invitation already accepted")
  public void revoke(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "invitationId", pipe = Param.Pipe.UUID) String invitationId,
      @CurrentUser("id") String actorId) {
    invitationService.revoke(projectId, invitationId, actorId);
  }
}
```

There is no `invitation.module.ts` to update with `controllers: [...]`/`providers: [...]` — `@RestController` and `@Service` are already component-scanned.

- [ ] **Step 5: Run tests, build, commit**

```bash
mvn test -Dtest='InvitationServiceTest' && mvn -q compile
git add src/main/java/com/kanban/modules/invitation src/main/java/com/kanban/common/validation/IsIn.java src/main/java/com/kanban/common/validation/ClassValidator.java
git commit -m "feat(invitation): create, list, revoke project invitations with hashed tokens

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Cover the new `@IsIn` branch in `ClassValidatorTest`**

The plan has no equivalent step here — class-validator's `@IsIn` ships pre-tested, but this port's new annotation (Step 3) has its own branch in `ClassValidator.check()` and needs its own coverage. Add a case to `src/test/java/com/kanban/common/validation/ClassValidatorTest.java`, in the same style as its existing `isEnum()` case:

```java
  @Test
  @DisplayName("IsIn rejects a value outside the explicit allow-list (owner), unlike IsEnum which allows every enum value")
  void isIn() {
    Map<String, Object> rejected = Json.map("email", "a@b.com", "role", "owner");
    assertThatThrownBy(() -> ClassValidator.validate(CreateInvitationDto.class, rejected))
        .isInstanceOf(BadRequestException.class)
        .satisfies(e -> assertThat(messagesOf(e)).containsExactly(
            "role must be one of the following values: admin, member, viewer"));

    CreateInvitationDto allowed = ClassValidator.validate(CreateInvitationDto.class,
        Json.map("email", "a@b.com", "role", "viewer"));
    assertThat(allowed.role).isEqualTo(ProjectRole.VIEWER);
  }
```

Also add a one-line javadoc note to `IsIn.java` recording that its allow-list is hand-maintained against the enum it constrains:

```java
 * <p>The literal {@code value()} allow-list is not derived from the enum, so it
 * must be kept in sync by hand with the referenced enum's wire values (e.g.
 * {@code CreateInvitationDto.role}'s {@code {"admin", "member", "viewer"}}
 * against {@code ProjectRole}'s wire values) whenever that enum changes.
```

```bash
mvn test -Dtest='ClassValidatorTest'
git add src/main/java/com/kanban/common/validation/IsIn.java src/test/java/com/kanban/common/validation/ClassValidatorTest.java
git commit -m "test(validation): cover the IsIn allow-list branch added for invitation roles

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 9: Invitation acceptance + in-app notification

**Files:**
- Create: `src/main/java/com/kanban/modules/invitation/dto/AcceptInvitationDto.java`
- Modify: `src/main/java/com/kanban/modules/invitation/InvitationService.java` (add `accept`, widen the constructor with `ProjectMemberRepository`/`ProjectService`/`EventBus`, add the notification emit in `create`)
- Modify: `src/main/java/com/kanban/modules/invitation/InvitationController.java` (add `POST /invitations/accept`)
- Modify: `src/main/java/com/kanban/modules/invitation/ProjectInvitationRepository.java` (add `findByTokenHash`)
- Modify: `src/main/java/com/kanban/modules/notification/NotificationType.java` (add `PROJECT_INVITED`)
- Modify: `src/main/java/com/kanban/modules/notification/events/NotificationEvents.java` (add the event-name constant) + Create: `src/main/java/com/kanban/modules/notification/events/ProjectInvitedEvent.java`
- Modify: `src/main/java/com/kanban/modules/notification/NotificationListener.java` (add handler)
- Modify: `src/main/java/com/kanban/modules/events/EventsService.java` (add WS handler)
- Create: `src/main/resources/db/migration/V3__add_project_invited_notification_type.sql`
- Modify: `src/test/java/com/kanban/modules/invitation/InvitationServiceTest.java` (add `Accept` nested class + notification-emit cases)

**Interfaces:**
- Consumes: `InvitationService` internals (Task 8), `NotificationService.createBatch` (via `NotificationListener`), `EventsService`'s recipient-fan-out helper.
- Produces:
  - `InvitationService.accept(String token, User user): Project` — validates, adds membership + marks accepted atomically, returns the joined project.
  - `NotificationType.PROJECT_INVITED = "project_invited"`.
  - `NotificationEvents.PROJECT_INVITED = "notification.project.invited"` and `record ProjectInvitedEvent(String actor_id, String entity_id, List<String> recipient_ids, Map<String, Object> payload) implements BaseNotificationEvent` with `entity_type() = "project_invitation"`, `payload: { project_id, project_name, role, inviter: { id, full_name, avatar_url } }`.

- [ ] **Step 1: Add failing accept tests to `InvitationServiceTest`**

`setUp()` widens: `invitationRepository`/`userRepository`/`projectAccessService` stay, and `memberRepository` (`ProjectMemberRepository`), `projectService` (`ProjectService`), and a `RecordingEventBus` (this port's in-memory `EventBus` test double, replacing a mocked `eventEmitter.emit`) are added and threaded into the widened `InvitationService` constructor:

```java
  private ProjectInvitationRepository invitationRepository;
  private UserRepository userRepository;
  private ProjectAccessService projectAccessService;
  private ProjectMemberRepository memberRepository;
  private ProjectService projectService;
  private RecordingEventBus eventBus;
  private InvitationService service;

  @BeforeEach
  void setUp() {
    invitationRepository = mock(ProjectInvitationRepository.class);
    userRepository = mock(UserRepository.class);
    projectAccessService = mock(ProjectAccessService.class);
    memberRepository = mock(ProjectMemberRepository.class);
    projectService = mock(ProjectService.class);
    eventBus = new RecordingEventBus();
    service = new InvitationService(invitationRepository, userRepository, projectAccessService, memberRepository,
        projectService, eventBus);

    when(invitationRepository.saveAndFlush(any(ProjectInvitation.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }
```

Two new `Create` cases assert the emit via `RecordingEventBus.emittedOf(...)` rather than a Mockito `verify(...)` on a mocked emitter — this test-double idiom is already established (see `CommentServiceTest`):

```java
    @Test
    @DisplayName("emits PROJECT_INVITED when the invitee already has an account")
    void emitsProjectInvitedWhenInviteeExists() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      User invitee = new User("invitee", "a@b.com", "Invitee", null, null, true);
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(invitee));
      when(projectAccessService.getMembership("proj1234", "invitee")).thenReturn(null);
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());
      User inviter = new User("actor", "actor@b.com", "Actor Name", null, "http://avatar", true);
      when(userRepository.findById("actor")).thenReturn(Optional.of(inviter));
      Project project = new Project();
      project.setId("proj1234");
      project.setName("Project One");
      when(projectService.findOneById("proj1234")).thenReturn(project);

      service.create("proj1234", dto("a@b.com", null), "actor");

      assertThat(eventBus.emittedOf(NotificationEvents.PROJECT_INVITED)).hasSize(1);
      ProjectInvitedEvent event = (ProjectInvitedEvent) eventBus.emittedOf(NotificationEvents.PROJECT_INVITED).get(0);
      assertThat(event.actor_id()).isEqualTo("actor");
      assertThat(event.entity_type()).isEqualTo("project_invitation");
      assertThat(event.recipient_ids()).containsExactly("invitee");
      assertThat(event.payload())
          .containsEntry("project_id", "proj1234")
          .containsEntry("project_name", "Project One")
          .containsEntry("role", ProjectRole.MEMBER);
      @SuppressWarnings("unchecked")
      Map<String, Object> inviterJson = (Map<String, Object>) event.payload().get("inviter");
      assertThat(inviterJson)
          .containsEntry("id", "actor")
          .containsEntry("full_name", "Actor Name")
          .containsEntry("avatar_url", "http://avatar");
    }

    @Test
    @DisplayName("does not emit PROJECT_INVITED when the invitee has no account")
    void doesNotEmitProjectInvitedWhenInviteeMissing() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("actor"), any()))
          .thenReturn(new ProjectMember("proj1234", "actor", ProjectRole.ADMIN));
      when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
      when(invitationRepository.findPendingByProjectIdAndEmail(anyString(), anyString(), any(Instant.class)))
          .thenReturn(Optional.empty());

      service.create("proj1234", dto("a@b.com", null), "actor");

      assertThat(eventBus.emittedOf(NotificationEvents.PROJECT_INVITED)).isEmpty();
    }
```

The `Accept` nested class (7 cases, matching the plan 1:1):

```java
  @Nested
  class Accept {
    private static final String RAW_TOKEN = "a".repeat(64);
    private final User jane = new User("jane-id", "Jane@Example.com", "Jane", null, null, true);

    private ProjectInvitation validInvitation() {
      ProjectInvitation invitation = new ProjectInvitation();
      invitation.setId("inv-uuid");
      invitation.setProjectId("proj1234");
      invitation.setEmail("jane@example.com");
      invitation.setRole(ProjectRole.MEMBER);
      invitation.setExpiresAt(Instant.now().plusSeconds(60));
      invitation.setAcceptedAt(null);
      invitation.setRevokedAt(null);
      return invitation;
    }

    @Test
    @DisplayName("rejects an unknown token with the generic 400")
    void rejectsUnknownToken() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects an expired invitation")
    void rejectsExpiredInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setExpiresAt(Instant.now().minusMillis(1));
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects a revoked invitation")
    void rejectsRevokedInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setRevokedAt(Instant.now());
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects reuse of an accepted invitation")
    void rejectsAcceptedInvitation() {
      ProjectInvitation invitation = validInvitation();
      invitation.setAcceptedAt(Instant.now());
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("rejects a user whose email does not match, with the same generic 400")
    void rejectsEmailMismatch() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(validInvitation()));
      User mallory = new User("mallory", "mallory@evil.com", "Mallory", null, null, true);

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, mallory))
          .isInstanceOf(BadRequestException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 400)
              .containsEntry("message", "Invalid or expired invitation"));
    }

    @Test
    @DisplayName("409s when the accepting user is already a member")
    void conflictsWhenAlreadyMember() {
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(validInvitation()));
      when(projectAccessService.getMembership("proj1234", "jane-id"))
          .thenReturn(new ProjectMember("proj1234", "jane-id", ProjectRole.MEMBER));

      assertThatThrownBy(() -> service.accept(RAW_TOKEN, jane))
          .isInstanceOf(ConflictException.class)
          .satisfies(e -> assertThat(response(e))
              .containsEntry("statusCode", 409)
              .containsEntry("message", "You are already a member of this project"));
    }

    @Test
    @DisplayName("looks the invitation up by sha256(token), creates the membership, marks accepted, returns the project")
    void acceptsAndJoinsProject() {
      ProjectInvitation invitation = validInvitation();
      when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.of(invitation));
      when(projectAccessService.getMembership("proj1234", "jane-id")).thenReturn(null);
      Project project = new Project();
      project.setId("proj1234");
      project.setName("P");
      when(projectService.findOneById("proj1234")).thenReturn(project);

      Project result = service.accept(RAW_TOKEN, jane);

      verify(invitationRepository).findByTokenHash(sha256(RAW_TOKEN));
      ArgumentCaptor<ProjectMember> memberCaptor = ArgumentCaptor.forClass(ProjectMember.class);
      verify(memberRepository).save(memberCaptor.capture());
      assertThat(memberCaptor.getValue().getProjectId()).isEqualTo("proj1234");
      assertThat(memberCaptor.getValue().getUserId()).isEqualTo("jane-id");
      assertThat(memberCaptor.getValue().getRole()).isEqualTo(ProjectRole.MEMBER);
      assertThat(invitation.getAcceptedAt()).isNotNull();
      assertThat(invitation.getAcceptedBy()).isEqualTo("jane-id");
      verify(invitationRepository).save(invitation);
      assertThat(result).isSameAs(project);
    }
  }
```

Note the last case's genuine gap versus the plan: the plan's Jest spec additionally asserts `expect(dataSource.transaction).toHaveBeenCalled()`. This port's transactional idiom is a declarative `@Transactional` on the service method (see `ProjectService.removeMembers`) — there is no injected transaction-manager object to spy a call on, so no equivalent assertion exists. The other assertions (hash lookup, membership row contents, `accepted_at`/`accepted_by`, returned project) are ported 1:1.

- [ ] **Step 2: Run to verify failure**

```bash
mvn test -Dtest='InvitationServiceTest'
```

Expected: FAIL — compilation error, `accept` does not exist yet on `InvitationService` (and the constructor arity mismatch).

- [ ] **Step 3: Implement accept + DTO + route**

`src/main/java/com/kanban/modules/invitation/dto/AcceptInvitationDto.java`:

```java
package com.kanban.modules.invitation.dto;

import com.kanban.common.validation.IsString;
import com.kanban.common.validation.MaxLength;
import com.kanban.common.validation.MinLength;
import com.kanban.common.validation.ValidatedDto;
import io.swagger.v3.oas.annotations.media.Schema;

public class AcceptInvitationDto extends ValidatedDto {
  @Schema(description = "Raw invitation token from the invite link (64 hex chars)", example = "3f1a...")
  @IsString
  @MinLength(64)
  @MaxLength(64)
  public String token;
}
```

`common/validation` has no combined `@Length(min, max)`; `@MinLength(64)` + `@MaxLength(64)` on the same field is exactly equivalent (`ClassValidator` evaluates each independently) — no new annotation class was needed here, unlike `@IsIn` in Task 8.

Add `findByTokenHash` to `ProjectInvitationRepository`:

```java
  Optional<ProjectInvitation> findByTokenHash(String tokenHash);
```

Widen `InvitationService`'s constructor and add `accept`:

```java
  private final ProjectInvitationRepository invitationRepository;
  private final UserRepository userRepository;
  private final ProjectAccessService projectAccessService;
  private final ProjectMemberRepository memberRepository;
  private final ProjectService projectService;
  private final EventBus eventBus;

  public InvitationService(ProjectInvitationRepository invitationRepository, UserRepository userRepository,
      ProjectAccessService projectAccessService, ProjectMemberRepository memberRepository,
      ProjectService projectService, EventBus eventBus) {
    this.invitationRepository = invitationRepository;
    this.userRepository = userRepository;
    this.projectAccessService = projectAccessService;
    this.memberRepository = memberRepository;
    this.projectService = projectService;
    this.eventBus = eventBus;
  }
```

```java
  /**
   * Accept an invitation by its raw token. Every invalid-token condition
   * (unknown/expired/revoked/used/wrong email) throws the SAME generic 400 so
   * this endpoint cannot be used as an oracle for token validity or invitee
   * emails. Only after that check passes is membership conflict distinguished
   * (409). Membership creation + marking the invitation accepted happen
   * atomically.
   */
  @Transactional
  public Project accept(String token, User user) {
    ProjectInvitation invitation = invitationRepository.findByTokenHash(hashToken(token)).orElse(null);
    if (invitation == null || isInvalidFor(invitation, user)) {
      throw invalidInvitation();
    }

    ProjectMember membership = projectAccessService.getMembership(invitation.getProjectId(), user.getId());
    if (membership != null) {
      throw new ConflictException(Json.map(
          "statusCode", 409,
          "message", "You are already a member of this project"));
    }

    memberRepository.save(new ProjectMember(invitation.getProjectId(), user.getId(), invitation.getRole()));
    invitation.setAcceptedAt(Instant.now());
    invitation.setAcceptedBy(user.getId());
    invitationRepository.save(invitation);

    return projectService.findOneById(invitation.getProjectId());
  }

  private static boolean isInvalidFor(ProjectInvitation invitation, User user) {
    return invitation.getRevokedAt() != null
        || invitation.getAcceptedAt() != null
        || !invitation.getExpiresAt().isAfter(Instant.now())
        || !invitation.getEmail().equals(user.getEmail().trim().toLowerCase(Locale.ROOT));
  }

  private static BadRequestException invalidInvitation() {
    return new BadRequestException(Json.map(
        "statusCode", 400,
        "message", "Invalid or expired invitation"));
  }
```

`@Transactional` replaces `dataSource.transaction(...)`, and is placed on the whole method rather than wrapped only around the membership-insert + invitation-save pair — the classic Spring self-invocation pitfall means a private `@Transactional` helper called from within the same class would silently not be proxied, so the full read-then-write sequence is annotated instead (matching `ProjectService.removeMembers`'s existing precedent).

Add the accept route. The NestJS `InvitationController` is `@Controller()` with no class-level prefix and a full path per method (`@Post('invitations/accept')` alongside the three `projects/:projectId/invitations` routes) — but Task 8's Spring port used a class-level `@RequestMapping("/projects/{projectId}/invitations")` (this port's usual one-controller-per-module idiom, mirroring e.g. `TeamController`). Adding `/invitations/accept` under that prefix would resolve to `/projects/{projectId}/invitations/invitations/accept`, not the required top-level route — so the controller drops its class-level `@RequestMapping` and every method now carries its own absolute path, exactly like `CommentController` (which has no class prefix either, for the same reason: `@PostMapping("/tasks/{taskId}/comments")`, `@PatchMapping("/comments/{id}")`, etc., side by side under one controller class):

```java
package com.kanban.modules.invitation;

import com.kanban.common.api.ApiListResponse;
import com.kanban.common.pipes.Param;
import com.kanban.common.validation.ValidatedBody;
import com.kanban.modules.auth.decorators.CurrentUser;
import com.kanban.modules.auth.guards.JwtAuth;
import com.kanban.modules.invitation.dto.AcceptInvitationDto;
import com.kanban.modules.invitation.dto.CreateInvitationDto;
import com.kanban.modules.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Project Invitations")
@RestController
public class InvitationController {
  private final InvitationService invitationService;

  public InvitationController(InvitationService invitationService) {
    this.invitationService = invitationService;
  }

  @PostMapping("/projects/{projectId}/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Create an invitation (admin+; owner to invite as admin). "
      + "The raw token is returned only in this response.")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "201", description = "Invitation created (includes one-time token)")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  @ApiResponse(responseCode = "409", description = "Already a member or already invited")
  public Map<String, Object> create(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @ValidatedBody CreateInvitationDto dto, @CurrentUser("id") String actorId) {
    return invitationService.create(projectId, dto, actorId);
  }

  @GetMapping("/projects/{projectId}/invitations")
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "List pending invitations (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @ApiResponse(responseCode = "200", description = "List of pending invitations")
  @ApiResponse(responseCode = "403", description = "Insufficient project role")
  @ApiResponse(responseCode = "404", description = "Project not found")
  public ApiListResponse<Map<String, Object>> findPending(
      @Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @CurrentUser("id") String actorId) {
    return invitationService.findPending(projectId, actorId);
  }

  @DeleteMapping("/projects/{projectId}/invitations/{invitationId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Revoke a pending invitation (admin+)")
  @Parameter(name = "projectId", description = "Project ID")
  @Parameter(name = "invitationId", description = "Invitation UUID")
  @ApiResponse(responseCode = "204", description = "Invitation revoked")
  @ApiResponse(responseCode = "404", description = "Project or invitation not found")
  @ApiResponse(responseCode = "409", description = "Invitation already accepted")
  public void revoke(@Param(value = "projectId", pipe = Param.Pipe.PROJECT_ID) String projectId,
      @Param(value = "invitationId", pipe = Param.Pipe.UUID) String invitationId,
      @CurrentUser("id") String actorId) {
    invitationService.revoke(projectId, invitationId, actorId);
  }

  @PostMapping("/invitations/accept")
  @ResponseStatus(HttpStatus.OK)
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @Operation(summary = "Accept an invitation by token (must be logged in as the invited email)")
  @ApiResponse(responseCode = "200", description = "Joined the project; returns the project")
  @ApiResponse(responseCode = "400", description = "Invalid or expired invitation")
  @ApiResponse(responseCode = "409", description = "Already a member")
  public Map<String, Object> accept(@ValidatedBody AcceptInvitationDto dto, @CurrentUser User user) {
    return invitationService.accept(dto.token, user).toJson(true);
  }
}
```

(`@CurrentUser User user` — no property name — resolves the full `User`: `JwtAuthInterceptor` reloads and stores it on the request per call, so `user.getEmail()` is live, matching the plan's note that the JWT strategy reloads the user per request.)

`InvitationService.accept` returns the bare `Project` entity (matching the interface contract), but the controller always calls `.toJson(...)` before handing anything to Jackson — no entity in this port carries its own snake_case/`@JsonProperty` wiring — so `accept`'s controller method returns `invitationService.accept(dto.token, user).toJson(true)`, the same pattern `ProjectController.findOne` uses for a single-project fetch.

- [ ] **Step 4: Wire the in-app notification**

1. `NotificationType.java` — add:

```java
  PROJECT_INVITED("project_invited");
```

2. `NotificationEvents.java` — add:

```java
  public static final String PROJECT_INVITED = "notification.project.invited";
```

and the event record:

```java
package com.kanban.modules.notification.events;

import java.util.List;
import java.util.Map;

public record ProjectInvitedEvent(String actor_id, String entity_id, List<String> recipient_ids,
    Map<String, Object> payload) implements BaseNotificationEvent {
  @Override
  public String entity_type() {
    return "project_invitation";
  }
}
```

3. `NotificationListener.java` — add (same shape as the four existing handlers):

```java
  @Async("eventExecutor")
  @EventListener
  public void handleProjectInvited(ProjectInvitedEvent event) {
    handle(event, NotificationType.PROJECT_INVITED, "project.invited");
  }
```

4. `EventsService.java` — add (same shape as existing handlers):

```java
  @Async("eventExecutor")
  @EventListener
  public void handleProjectInvited(ProjectInvitedEvent event) {
    emitToRecipients(event, "project_invited");
  }
```

5. In `InvitationService.create`, replace the `// Task 9 adds:` comment with:

```java
    // In-app notification when the invitee already has an account.
    if (invitee != null) {
      Project project = projectService.findOneById(projectId);
      User inviter = userRepository.findById(actorId).orElse(null);
      eventBus.emit(NotificationEvents.PROJECT_INVITED, new ProjectInvitedEvent(actorId, saved.getId(),
          List.of(invitee.getId()), Json.map(
              "project_id", projectId,
              "project_name", project.getName(),
              "role", role,
              "inviter", Json.map(
                  "id", actorId,
                  "full_name", inviter != null ? inviter.getFullName() : "",
                  "avatar_url", inviter != null ? inviter.getAvatarUrl() : null))));
    }
```

6. Migration `src/main/resources/db/migration/V3__add_project_invited_notification_type.sql`:

```sql
ALTER TYPE notifications_type_enum ADD VALUE IF NOT EXISTS 'project_invited';
```

The plan's migration note says the type is named `notification_type`; that name is wrong for this schema. This repo's `V1` baseline creates the notifications enum as `notifications_type_enum`, and `V3` must target that exact name for the `ALTER TYPE` to succeed — there is no `synchronize`-vs-migration ambiguity to caveat here, since this port always runs migrations.

- [ ] **Step 5: Run tests, build, commit**

```bash
mvn test -Dtest='InvitationServiceTest' && mvn -q compile
git add src/main/java/com/kanban/modules/invitation src/main/java/com/kanban/modules/notification src/main/java/com/kanban/modules/events src/main/resources/db/migration/V3__add_project_invited_notification_type.sql src/test/java/com/kanban/modules/invitation/InvitationServiceTest.java
git commit -m "feat(invitation): token acceptance with email binding and in-app notification

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: Consolidate the two invitation controllers**

Step 3 above already lands on the single-controller, absolute-path-per-method shape — but getting there for real required a follow-up fix. Wiring `POST /invitations/accept` onto Task 8's `InvitationController` (class-level `@RequestMapping("/projects/{projectId}/invitations")`) is not possible without breaking that prefix, so the first pass added a second controller, `InvitationAcceptController` (`@RequestMapping("/invitations")`, one `@PostMapping("/accept")` method), rather than touching the three already-committed Task 8 routes. That introduced a structural pattern with zero precedent elsewhere in the codebase — every other module keeps exactly one controller class, and a precedent-matching alternative already existed (`CommentController`'s no-class-prefix, full-path-per-method idiom). The fix deletes `InvitationAcceptController` and folds its one route into `InvitationController`, converting all four methods to absolute paths as shown in Step 3.

```bash
mvn test -Dtest='InvitationServiceTest' && mvn -q compile
git add src/main/java/com/kanban/modules/invitation
git commit -m "refactor(invitation): consolidate controllers per port idiom

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---
## Task 10: Secure project, board, and column routes

**Files:**
- Modify: `src/main/java/com/kanban/modules/project/ProjectController.java`, `ProjectService.java` (`findAll` scoped)
- Modify: `src/main/java/com/kanban/modules/board/BoardController.java`
- Modify: `src/main/java/com/kanban/modules/kanbancolumn/KanbanColumnController.java`, `KanbanColumnService.java`, `KanbanColumnRepository.java`

**Interfaces:**
- Consumes: `@RequireProjectRole` + `ProjectRoleInterceptor` (Task 4), `ProjectAccessService` (Task 2) — both already implemented and globally registered (`WebMvcConfig.addInterceptors`) at HEAD.
- Produces: `ProjectService.findAll(String userId): List<Project>` (membership-scoped); `KanbanColumnService` methods take a required `String actorId` as their last parameter.

Note (applies to every task below, stated once): the plan's NestJS "add `ProjectModule` to this module's imports" steps have no Java equivalent — this port has no per-feature modules, just `@Service`/`@RestController` beans wired by Spring's classpath scanning. Wherever the plan says to add a module import, there is nothing to do.

- [ ] **Step 1: Project routes**

In `ProjectController.java` — import `com.kanban.modules.project.guards.RequireProjectRole` and `com.kanban.modules.project.ProjectRole`, then:

| Route | Change |
|---|---|
| `GET /projects` | add `@JwtAuth` + `@SecurityRequirement(name = "bearer")`; handler becomes `findAll(@CurrentUser("id") String userId)` → `projectService.findAll(userId)` |
| `GET /projects/{id}` | add `@JwtAuth` + `@SecurityRequirement(name = "bearer")` + `@RequireProjectRole(value = ProjectRole.VIEWER, param = "id")` |
| `PATCH /projects/{id}` | same, `@RequireProjectRole(value = ProjectRole.ADMIN, param = "id")` |
| `DELETE /projects/{id}` | same, `@RequireProjectRole(value = ProjectRole.OWNER, param = "id")` |
| `GET /projects/{id}/members` | same, `@RequireProjectRole(value = ProjectRole.VIEWER, param = "id")` |

`param = "id"` is required on every one of these — the controller's path variable is `{id}`, not the interceptor's default `{projectId}`. (`POST`/`DELETE /projects/{id}/members` and `PATCH /projects/{id}/members/{userId}` already carry `@JwtAuth` only, unchanged — their policy lives in `ProjectService`.) Add `@ApiResponse(responseCode = "403", ...)` / `"404"` entries where newly applicable, matching the sibling routes already in the file.

In `ProjectService.java`, replace `findAll()` with a membership-scoped version (keep the method name; per the "never leak internals" rule this is a full rewrite, not an edit next to legacy code, so it gets no try/catch at all — an unexpected failure falls through to the global 500 handler):

```java
public List<Project> findAll(String userId) {
  List<ProjectMember> memberships = memberRepository.findByUserIdWithProjectOrderByJoinedAtDesc(userId);
  return memberships.stream().map(ProjectMember::getProject).toList();
}
```

This reuses `ProjectMemberRepository.findByUserIdWithProjectOrderByJoinedAtDesc` — already defined (it backs `UserService.findProjects`) with `@EntityGraph(attributePaths = {"project", "project.creator"})` and `order by m.joinedAt desc`, i.e. exactly the plan's `relations: ['project', 'project.creator']` / `order: { joined_at: 'DESC' }`. No repository change needed for this step.

- [ ] **Step 2: Board route**

`BoardController.java` already carries a class-level `@JwtAuth` (this port's one deliberate divergence from Nest — see `MIGRATION.md` §2). Add to the `getBoard` method:

```java
@RequireProjectRole(value = ProjectRole.VIEWER)
@SecurityRequirement(name = "bearer")
```

(imports: `com.kanban.modules.project.guards.RequireProjectRole`, `com.kanban.modules.project.ProjectRole`, `io.swagger.v3.oas.annotations.security.SecurityRequirement`.) No `param` override needed — the path variable is already `{projectId}`, matching the interceptor's default. `@ApiResponse(401)` is already present on this method.

- [ ] **Step 3: Column routes**

`KanbanColumnRepository.java`: add

```java
List<KanbanColumn> findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(List<String> projectIds);
```

`KanbanColumnService.java`: inject `private final ProjectAccessService projectAccessService;` (new constructor parameter; import `com.kanban.modules.project.ProjectAccessService` and `com.kanban.modules.project.ProjectRole`). Every route in `KanbanColumnController` becomes `@JwtAuth` + `@SecurityRequirement(name = "bearer")`, and the controller passes `@CurrentUser("id") String actorId` on every route:

- `create(CreateKanbanColumnDto dto, String actorId)`: first line `projectAccessService.ensureRole(dto.project_id, actorId, ProjectRole.ADMIN);`. This message is byte-identical to the existing manual check (`"Project with id \"" + projectId + "\" not found"`), so **delete** the now-redundant `if (!projectRepository.existsById(dto.project_id)) throw projectNotFound(...)` block above it.
- `findAll(String actorId)`: `List<String> projectIds = projectAccessService.getProjectIdsForUser(actorId); if (projectIds.isEmpty()) return ApiListResponse.ok(List.of());` then query `columnRepository.findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(projectIds)` instead of `findByIsArchivedFalseOrderByPositionAsc()`.
- `findOneById(int id, String actorId)`: first line `projectAccessService.ensureColumnRole(id, actorId, ProjectRole.VIEWER);` — use the existing column-flavored gate directly (single call); there is no need for the plan's two-step `getProjectIdForColumn` + `ensureRole`, since `ensureColumnRole` already does both and 404-masks with the column's own message.
- `update(int id, UpdateKanbanColumnDto dto, String actorId)` and `remove(int id, String actorId)`: same, `projectAccessService.ensureColumnRole(id, actorId, ProjectRole.ADMIN);` as the first line, before the existing `try {`.

Leave `update`'s separate `dto.project_id`-exists check untouched — it validates a *different* project (the column's prospective new one when the caller re-parents it), which `ensureColumnRole` (scoped to the column's *current* project) does not cover.

- [ ] **Step 4: Verify, commit**

```bash
mvn -q compile && mvn test
git add src/main/java/com/kanban/modules/project src/main/java/com/kanban/modules/board src/main/java/com/kanban/modules/kanbancolumn
git commit -m "feat(rbac): scope project, board, and column routes to membership

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 11: Secure task-scoped routes (tasks, comments, subscriptions, activities)

**Files:**
- Modify: `src/main/java/com/kanban/modules/task/TaskController.java`, `TaskService.java`, `TaskRepository.java`; `src/test/java/com/kanban/modules/task/TaskServiceTest.java`
- Modify: `src/main/java/com/kanban/modules/comment/CommentController.java`, `CommentService.java`
- Modify: `src/main/java/com/kanban/modules/subscription/SubscriptionController.java`
- Modify: `src/main/java/com/kanban/modules/activity/ActivityController.java`, `ActivityService.java`

**Interfaces:**
- Consumes: `ProjectAccessService.ensureTaskRole` / `ensureRole` / `getProjectIdsForUser` (Task 2).
- Produces (`TaskService` — controllers call these):
  - `findAllForUser(String userId): List<Task>` (replaces controller use of `findAll`; the old unscoped `findAll` is **deleted**)
  - `findOneForUser(String id, String userId): Task`
  - `findByTicketIdForUser(String ticketId, String userId): Task`
  - `findSubtasksForUser(String parentId, String userId): ApiListResponse<Map<String, Object>>`
  - All mutating methods take a **required** `String actorId` (`remove(id, actorId)` and `reorderSubtask(id, subtaskId, position, actorId)` gain the parameter — every other mutating method already has it).

Rule of thumb applied everywhere: reads check `ProjectRole.VIEWER`, mutations check `ProjectRole.MEMBER`, all via `ensureTaskRole` (which also 404s unknown tasks — keeping the masking).

- [ ] **Step 1: Add failing tests to `TaskServiceTest.java`**

Add a `ProjectAccessService` field and thread it into the constructor call in `setUp()`:

```java
import com.kanban.common.exception.ForbiddenException;
import com.kanban.common.exception.NotFoundException;
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectRole;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

  private ProjectAccessService projectAccessService;

  @BeforeEach
  void setUp() {
    // ...existing mocks...
    projectAccessService = mock(ProjectAccessService.class);
    service = new TaskService(taskRepo, userRepo, labelRepo, columnRepo, positions, events, subscription, mention,
        projectAccessService);
  }
```

and a new nested class:

```java
  @Nested
  class Authorization {
    @Test
    @DisplayName("update rejects a viewer (403 propagates from the gate)")
    void rejectsViewer() {
      when(projectAccessService.ensureTaskRole("task-1", "viewer-user", ProjectRole.MEMBER))
          .thenThrow(new ForbiddenException());
      UpdateTaskDto dto = new UpdateTaskDto();
      dto.title = "x";
      dto.with("title");

      assertThatThrownBy(() -> service.update("task-1", dto, "viewer-user"))
          .isInstanceOf(ForbiddenException.class);
      verify(projectAccessService).ensureTaskRole("task-1", "viewer-user", ProjectRole.MEMBER);
    }

    @Test
    @DisplayName("findOneForUser 404-masks tasks in projects the user is not a member of")
    void masksNonMember() {
      when(projectAccessService.ensureTaskRole("task-1", "outsider", ProjectRole.VIEWER))
          .thenThrow(new NotFoundException());
      assertThatThrownBy(() -> service.findOneForUser("task-1", "outsider")).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("findAllForUser returns [] for a user with no memberships without querying tasks")
    void noMemberships() {
      when(projectAccessService.getProjectIdsForUser("lonely")).thenReturn(List.of());
      assertThat(service.findAllForUser("lonely")).isEmpty();
      verifyNoInteractions(taskRepo);
    }
  }
```

Run `mvn test -Dtest=TaskServiceTest` — expected FAIL (constructor arity mismatch / missing methods). Existing nested classes (`Create`, `UpdateStatusFanOut`, `AddAssignees`, `DescriptionMentionResilience`) need no changes to compile once the constructor call above is updated — a `mock(ProjectAccessService.class)` returns `null` for any unstubbed call, which is harmless for methods that only invoke it for its side effect.

- [ ] **Step 2: TaskService changes**

1. Inject `private final ProjectAccessService projectAccessService;` as a new (ninth) constructor parameter; import `com.kanban.modules.project.ProjectAccessService` and `com.kanban.modules.project.ProjectRole`.
2. `create(CreateTaskDto dto, String actorId)`: capture `resolveColumn`'s return value (currently discarded) and gate on it:

```java
      KanbanColumn column = resolveColumn(dto.column_id);
      projectAccessService.ensureRole(column.getProjectId(), actorId, ProjectRole.MEMBER);
```

   replacing the bare `resolveColumn(dto.column_id);` call. Also modernize this method's legacy rethrow guard from `catch (NotFoundException | BadRequestException e) { throw e; }` to `catch (HttpException e) { throw e; }` (import `com.kanban.common.exception.HttpException`) — do this in every method you touch in this task, per the constraints' legacy-rethrow-guard rule.
3. Replace `findAll()`:

```java
  public List<Task> findAllForUser(String userId) {
    List<String> projectIds = projectAccessService.getProjectIdsForUser(userId);
    if (projectIds.isEmpty()) {
      return List.of();
    }
    return taskRepository.findTopLevelWithRelationsByProjectIds(projectIds);
  }
```

(delete the old `findAll()`.) Add to `TaskRepository.java` — `Task` has no `@ManyToOne` relation to `KanbanColumn` (just a plain `columnId` field), so the project scoping is a subquery rather than a relation-path filter:

```java
  @EntityGraph(attributePaths = {"assignees", "labels", "creator", "subtasks", "subtasks.parent"})
  @Query("select t from Task t where t.parentId is null and t.columnId in "
      + "(select c.id from KanbanColumn c where c.projectId in :projectIds)")
  List<Task> findTopLevelWithRelationsByProjectIds(@Param("projectIds") List<String> projectIds);
```

4. Add read wrappers (keep `findOneById`/`findByTicketId`/`findSubtasks` as internal loaders, unchanged):

```java
  public Task findOneForUser(String id, String userId) {
    projectAccessService.ensureTaskRole(id, userId, ProjectRole.VIEWER);
    return findOneById(id);
  }

  public Task findByTicketIdForUser(String ticketId, String userId) {
    Task task = findByTicketId(ticketId);
    projectAccessService.ensureTaskRole(task.getId(), userId, ProjectRole.VIEWER);
    return task;
  }

  public ApiListResponse<Map<String, Object>> findSubtasksForUser(String parentId, String userId) {
    projectAccessService.ensureTaskRole(parentId, userId, ProjectRole.VIEWER);
    return findSubtasks(parentId);
  }
```

5. For each mutating method — `update`, `reorder`, `move`, `remove`, `addAssignees`, `removeAssignees`, `addLabels`, `removeLabels`, `createSubtask`, `reorderSubtask` — add `String actorId` where missing (`remove(String id)` → `remove(String id, String actorId)`; `reorderSubtask(String parentId, String subtaskId, int position)` → append `String actorId`) and insert as the first statement, **before** the method's `try {`:

```java
    projectAccessService.ensureTaskRole(<taskIdParam>, actorId, ProjectRole.MEMBER);
```

where `<taskIdParam>` is the method's task-id argument (`id`, `taskId`, or the parent `id` for subtask methods). Placing it before the `try` means an authorization failure is never re-wrapped by the method's own legacy catch block. Note that `createSubtask` calls `create(...)` internally, so a subtask create re-runs `ensureRole` against the same project once more — a harmless, intentional double-check, not a bug to remove.
6. Update `TaskController`'s call sites accordingly (see Step 3): `remove(id, userId)`, `reorderSubtask(id, subtaskId, dto.position, userId)`.

- [ ] **Step 3: TaskController changes**

- Add `@JwtAuth` + `@SecurityRequirement(name = "bearer")` to the six unguarded routes: `GET /`, `GET /by-ticket/{ticketId}`, `GET /{id}`, `DELETE /{id}`, `GET /{id}/subtasks`, `PATCH /{id}/subtasks/{subtaskId}/reorder`.
- Add `@CurrentUser("id") String userId` to each and call the new methods: `findAllForUser(userId)`, `findByTicketIdForUser(ticketId, userId)`, `findOneForUser(id, userId)`, `remove(id, userId)`, `findSubtasksForUser(id, userId)`, `reorderSubtask(id, subtaskId, dto.position, userId)`.
- Add `@ApiResponse(responseCode = "403", description = "Requires member role")` on mutating routes.

- [ ] **Step 4: Comments, subscriptions, activities**

**`CommentService.java`**: inject `private final ProjectAccessService projectAccessService;` (import `ProjectAccessService`/`ProjectRole`).
- `create(String taskId, String authorId, CreateCommentDto dto)`: first line `projectAccessService.ensureTaskRole(taskId, authorId, ProjectRole.MEMBER);` (the existing `taskRepository.findById(taskId).orElseThrow(...)` line stays — it still loads the `Task` the method needs later).
- `findByTask(String taskId, CommentQueryDto query, String userId)` — add the `String userId` parameter; first line `projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);`, replacing the existing `ensureTaskExists(taskId);` call. Since that private helper is now unused, delete it too.

**`CommentController`**: `GET /tasks/{taskId}/comments` gains `@JwtAuth` + `@SecurityRequirement(name = "bearer")` + `@CurrentUser("id") String userId`, calling `commentService.findByTask(taskId, query, userId)`.

**`SubscriptionController`** (judgment call — see the report at the end of this task's translation): this controller, unlike Comment/Activity, already runs its own precondition check inline (`subscriptionService.ensureTaskExists(taskId)`) in all four of `subscribe`, `unsubscribe`, `getMyStatus`, `listSubscribers` before delegating — an existing idiom in this file, not one introduced by this task. Following it (per "never introduce a new framework pattern when the port already has one"): inject `private final ProjectAccessService projectAccessService;` into `SubscriptionController`'s constructor and **replace** each `subscriptionService.ensureTaskExists(taskId)` call with `projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);` (import `ProjectRole`). `listSubscribers` gains a `@CurrentUser("id") String userId` parameter to support this. `SubscriptionService.java` itself needs no change — its `ensureTaskExists` becomes unused by the controller (leave it; it is still covered by its own unit test, and this task's scope is authorization, not dead-code cleanup). The internal auto-subscribe calls from `TaskService`/`CommentService` (`subscribe`, `subscribeMany`, invoked with `SubscriptionSource.ASSIGNED` etc.) are on `SubscriptionService` directly and are untouched by this change — the acting user was already authorized by the calling service, and recipients of an assignment may legitimately differ from the actor.

Update `src/test/java/com/kanban/modules/subscription/SubscriptionControllerTest.java` to match: construct the controller as `new SubscriptionController(service, projectAccessService)` with `projectAccessService = mock(ProjectAccessService.class);` stubbed in `setUp()` (`when(projectAccessService.ensureRole... )` — actually `ensureTaskRole(any(), any(), any())` — `.thenReturn("proj1")`); remove the `doNothing().when(service).ensureTaskExists(any());` stub (the controller no longer calls it); change the two "missing" tests to `doThrow(new NotFoundException()).when(projectAccessService).ensureTaskRole(eq("missing"), any(), any());` instead of stubbing `service.ensureTaskExists`; change `verify(service).ensureTaskExists("t1")` assertions to `verify(projectAccessService).ensureTaskRole("t1", "u1", ProjectRole.VIEWER)`; update `controller.listSubscribers("t1")` call sites to `controller.listSubscribers("t1", "u1")`.

**`ActivityService.java`**: `findByTask(String taskId, ActivityQueryDto query)` gains a `String userId` parameter; first line `projectAccessService.ensureTaskRole(taskId, userId, ProjectRole.VIEWER);`, replacing the inline `if (!taskRepository.existsById(taskId)) throw new NotFoundException(...)` check (inject `ProjectAccessService`/`ProjectRole`). **`ActivityController`** (already `@JwtAuth`) adds `@CurrentUser("id") String userId` and passes it to `activityService.findByTask(taskId, query, userId)`.

- [ ] **Step 5: Fix compile fallout, run everything, commit**

```bash
mvn -q compile 2>&1 | head -40   # chase remaining required-actorId call sites
mvn test
git add src/main/java/com/kanban/modules/task src/main/java/com/kanban/modules/comment \
        src/main/java/com/kanban/modules/subscription src/main/java/com/kanban/modules/activity \
        src/test/java/com/kanban/modules/task src/test/java/com/kanban/modules/subscription
git commit -m "feat(rbac): membership-scope all task, comment, subscription, activity routes

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Expected: compile green; full suite green (127+ tests) including the pre-existing `TaskServiceTest`, `CommentServiceTest`, `SubscriptionControllerTest` (updated per above — `CommentServiceTest` needs no change since `CommentService`'s constructor signature is unchanged from its perspective... actually it **does** change: add the `ProjectAccessService` mock to `CommentServiceTest`'s `setUp()` too, threading it into `new CommentService(commentRepo, taskRepo, events, subscription, mention, projectAccessService)`).

---

## Task 12: Secure team, user, and label routes

**Files:**
- Modify: `src/main/java/com/kanban/modules/team/TeamController.java` (guard the three read routes)
- Modify: `src/main/java/com/kanban/modules/user/UserController.java`, `UserService.java`
- Modify: `src/main/java/com/kanban/modules/label/LabelController.java`

**Interfaces:**
- Consumes: `@RequireProjectRole` + `ProjectRoleInterceptor` (Task 4).
- Produces: `UserService.findProjects(String userId, String callerId)` — 403 unless `userId.equals(callerId)`.

- [ ] **Step 1: Team reads**

`TeamController.java` — `GET /`, `GET /{teamId}`, `GET /{teamId}/members` each gain:

```java
  @JwtAuth
  @SecurityRequirement(name = "bearer")
  @RequireProjectRole(value = ProjectRole.VIEWER)
```

(imports: `com.kanban.modules.project.guards.RequireProjectRole`, `com.kanban.modules.project.ProjectRole`.) No `param` override — the class's `@RequestMapping("/projects/{projectId}/teams")` already matches the interceptor's default `"projectId"`. `TeamService` already has `ProjectAccessService` wired in (from the already-implemented Task 3) — no service changes needed for this step.

- [ ] **Step 2: User routes**

`UserController.java`:
- `GET /users` and `GET /users/{id}`: add `@JwtAuth` + `@SecurityRequirement(name = "bearer")` (the full user list stays visible to any authenticated user — it powers invite/mention pickers; noted as accepted exposure).
- `GET /users/{id}/projects`: add `@JwtAuth` + `@SecurityRequirement(name = "bearer")` + `@CurrentUser("id") String callerId`, call `findProjects(id, callerId)`, add `@ApiResponse(responseCode = "403", description = "Can only view your own projects")`.
- `GET /users/me/projects` (already `@JwtAuth` + `@CurrentUser("id") String userId`): change the call to `findProjects(userId, userId)`.

`UserService.java` — `findProjects` gains a `callerId` parameter and the check as its very first statement, **before** the existing `try`:

```java
  public ApiListResponse<Map<String, Object>> findProjects(String userId, String callerId) {
    if (!userId.equals(callerId)) {
      throw new ForbiddenException(Json.map(
          "statusCode", 403,
          "message", "You can only view your own projects"));
    }
    try {
      findOneById(userId);
      // ...existing body unchanged
```

(import `com.kanban.common.exception.ForbiddenException`.) Because the check runs before the `try`, the method's existing `catch (NotFoundException e) { throw e; }` guard needs no change — `ForbiddenException` never enters that block.

- [ ] **Step 3: Label routes**

`LabelController.java`: add `@JwtAuth` + `@SecurityRequirement(name = "bearer")` to all five routes (import `com.kanban.modules.auth.guards.JwtAuth` and `io.swagger.v3.oas.annotations.security.SecurityRequirement`). Labels are a global table (no `project_id` column) — project-scoping them is schema work, explicitly out of scope; JWT stops anonymous mutation only.

- [ ] **Step 4: Verify, commit**

```bash
mvn -q compile && mvn test
git add src/main/java/com/kanban/modules/team src/main/java/com/kanban/modules/user src/main/java/com/kanban/modules/label
git commit -m "feat(rbac): guard team, user, and label routes

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 13: Socket.IO board-room authorization

**Files:**
- Modify: `src/main/java/com/kanban/modules/events/EventsGateway.java` (add `handleBoardJoin` / `handleBoardLeave` / `emitToProject`)
- Modify: `src/main/java/com/kanban/modules/events/socket/SocketClient.java` (add `leave(String room)`), `src/main/java/com/kanban/modules/events/socket/NettySocketIoServer.java` (implement `leave`; register the two new event listeners)
- Modify: `src/test/java/com/kanban/testing/FakeSocketClient.java` (implement `leave`)
- Modify: `src/test/java/com/kanban/modules/events/EventsGatewayTest.java` (add tests)

**Interfaces:**
- Consumes: `ProjectAccessService.ensureRole` (Task 2).
- Produces: WS messages `board:join` / `board:leave` with payload `{"projectId": "..."}` (camelCase — the events module's established wire style); replies `board:join:success` / `board:join:error` / `board:leave:success`; room name `"project:" + projectId`; `EventsGateway.emitToProject(String projectId, String event, Object data)` for future board broadcasts.

Note: this port's `SocketClient` interface (the minimal socket.io-`Socket` surface the gateway needs) has `join` but no `leave` today — the plan's `client.leave(room)` calls have no counterpart yet. Adding it is part of this task's implementation step, not a pre-existing capability.

- [ ] **Step 1: Write the failing tests**

Add a `ProjectAccessService` field to `EventsGatewayTest` and thread it into the constructor:

```java
import com.kanban.modules.project.ProjectAccessService;
import com.kanban.modules.project.ProjectMember;
import com.kanban.modules.project.ProjectRole;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;

  private ProjectAccessService projectAccessService;

  @BeforeEach
  void setUp() {
    // ...existing mocks...
    projectAccessService = mock(ProjectAccessService.class);
    gateway = new EventsGateway(wsJwtGuard, events, projectAccessService);
    gateway.setServer(server);
  }
```

and a new nested class:

```java
  @Nested
  class BoardRooms {
    private FakeSocketClient clientFor(String userId) {
      FakeSocketClient client = new FakeSocketClient("socket-1", null);
      client.data().put("user", new User(userId, userId + "@example.com", "U", UserRole.BACKEND_DEVELOPER, null, true));
      return client;
    }

    @Test
    @DisplayName("joins project room after a successful membership check")
    void joins() {
      when(projectAccessService.ensureRole(eq("proj1234"), eq("user-1"), any())).thenReturn(mock(ProjectMember.class));
      FakeSocketClient client = clientFor("user-1");

      gateway.handleBoardJoin(client, Json.map("projectId", "proj1234"));

      verify(projectAccessService).ensureRole("proj1234", "user-1", ProjectRole.VIEWER);
      assertThat(client.joined).containsExactly("project:proj1234");
      assertThat(client.emittedEvent("board:join:success", Json.map("projectId", "proj1234"))).isTrue();
    }

    @Test
    @DisplayName("denies the join for non-members without joining the room")
    void denies() {
      when(projectAccessService.ensureRole(any(), any(), any())).thenThrow(new RuntimeException("no"));
      FakeSocketClient client = clientFor("user-1");

      gateway.handleBoardJoin(client, Json.map("projectId", "proj1234"));

      assertThat(client.joined).isEmpty();
      assertThat(client.emittedEvent("board:join:error", Json.map(
          "projectId", "proj1234",
          "message", "You do not have access to this project"))).isTrue();
    }

    @Test
    @DisplayName("rejects a join with no projectId payload")
    void rejectsMissingPayload() {
      FakeSocketClient client = clientFor("user-1");

      gateway.handleBoardJoin(client, Json.map());

      assertThat(client.joined).isEmpty();
      verifyNoInteractions(projectAccessService);
    }

    @Test
    @DisplayName("board:leave leaves the room")
    void leaves() {
      FakeSocketClient client = clientFor("user-1");
      gateway.handleBoardLeave(client, Json.map("projectId", "proj1234"));
      assertThat(client.left).containsExactly("project:proj1234");
    }

    @Test
    @DisplayName("emitToProject targets the project room")
    void emitsToProject() {
      Map<String, Object> data = Json.map("id", "t1");
      gateway.emitToProject("proj1234", "task:moved", data);
      verify(server).emitToRoom("project:proj1234", "task:moved", data);
    }
  }
```

Run `mvn test -Dtest=EventsGatewayTest` — expected FAIL (constructor arity mismatch / missing methods / `FakeSocketClient.left` doesn't exist yet).

- [ ] **Step 2: Implement**

`SocketClient.java`: add to the interface

```java
  /** {@code client.leave(room)} */
  void leave(String room);
```

`FakeSocketClient.java`: add a recording field and implement it

```java
  public final List<String> left = new ArrayList<>();

  @Override
  public void leave(String room) {
    left.add(room);
  }
```

`NettySocketIoServer.java`, in the inner `NettySocketClient` adapter: implement `leave` over the underlying client, and register the two new listeners in `start()` next to the existing `token:refresh` one:

```java
    @Override
    public void leave(String room) {
      client.leaveRoom(room);
    }
```

```java
    server.addEventListener("board:join", Object.class,
        (client, data, ack) -> gateway.handleBoardJoin(wrap(client), asMap(data)));
    server.addEventListener("board:leave", Object.class,
        (client, data, ack) -> gateway.handleBoardLeave(wrap(client), asMap(data)));
```

`EventsGateway.java`: inject `private final ProjectAccessService projectAccessService;` (new third constructor parameter; import `com.kanban.modules.project.ProjectAccessService` and `com.kanban.modules.project.ProjectRole`) and add:

```java
  /** {@code @SubscribeMessage('board:join')} */
  public void handleBoardJoin(SocketClient client, Map<String, Object> data) {
    Object userObj = client.data() == null ? null : client.data().get("user");
    String userId = userObj instanceof User u ? u.getId() : null;
    Object projectIdObj = data == null ? null : data.get("projectId");
    if (userId == null || !(projectIdObj instanceof String projectId) || projectId.isEmpty()) {
      client.emit("board:join:error", Json.map(
          "projectId", projectIdObj,
          "message", "You do not have access to this project"));
      return;
    }
    try {
      // Any membership (viewer+) may watch the board.
      projectAccessService.ensureRole(projectId, userId, ProjectRole.VIEWER);
      client.join("project:" + projectId);
      client.emit("board:join:success", Json.map("projectId", projectId));
    } catch (RuntimeException e) {
      client.emit("board:join:error", Json.map(
          "projectId", projectId,
          "message", "You do not have access to this project"));
    }
  }

  /** {@code @SubscribeMessage('board:leave')} */
  public void handleBoardLeave(SocketClient client, Map<String, Object> data) {
    Object projectIdObj = data == null ? null : data.get("projectId");
    if (!(projectIdObj instanceof String projectId) || projectId.isEmpty()) {
      return;
    }
    client.leave("project:" + projectId);
    client.emit("board:leave:success", Json.map("projectId", projectId));
  }

  public void emitToProject(String projectId, String event, Object data) {
    server.emitToRoom("project:" + projectId, event, data);
  }
```

Wait for the check to pass before `join` — never join-then-verify. The generic error message is intentional (no membership oracle over WS).

- [ ] **Step 3: Run tests, build, commit**

```bash
mvn test -Dtest=EventsGatewayTest && mvn -q compile
git add src/main/java/com/kanban/modules/events src/test/java/com/kanban/modules/events src/test/java/com/kanban/testing/FakeSocketClient.java
git commit -m "feat(events): membership-authorized project board rooms

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Task 14: Documentation + final verification

**Files:**
- Modify: `MIGRATION.md` (§2 "Module-by-module mapping" — route table 🔒 markers and the guard paragraph beneath it)

This repo has no `CLAUDE.md`; the equivalent authorization documentation lives in `MIGRATION.md`. `README.md` documents ports/setup/testing, not the authorization model, so it needs no change here.

- [ ] **Step 1: Update MIGRATION.md**

In §2's module route table, add 🔒 to every route this feature newly guards:

- `project`: `GET /projects`, `GET /projects/:id`, `PATCH /projects/:id`, `DELETE /projects/:id`, `GET /projects/:id/members`.
- `kanban-column`: all five routes (`POST /columns`, `GET /columns`, `GET /columns/:id`, `PATCH /columns/:id`, `DELETE /columns/:id`).
- `task`: `GET /tasks`, `GET /tasks/by-ticket/:ticketId`, `GET /tasks/:id`, `DELETE /tasks/:id`, `GET /tasks/:id/subtasks`, `PATCH /tasks/:id/subtasks/:subtaskId/reorder`.
- `comment`: `GET /tasks/:taskId/comments`.
- `team`: `GET /`, `GET /:teamId`, `GET /:teamId/members` (under `projects/:projectId/teams`).
- `user`: `GET /users`, `GET /users/:id`, `GET /users/:id/projects`.
- `label`: all five routes.
- `activity`: unchanged (already 🔒).

While there, also add the invitation module's row if it is still missing from the table (it was scaffolded by the already-implemented Tasks 7–9 but this doc was never updated for it): `invitation` → `com.kanban.modules.invitation` → `POST /projects/:projectId/invitations` 🔒 201, `GET /projects/:projectId/invitations` 🔒, `DELETE /projects/:projectId/invitations/:invitationId` 🔒 204, `POST /invitations/accept` 🔒 200; and the `project` row's missing `PATCH /projects/:id/members/:userId` 🔒 route.

Replace the paragraph directly beneath the table (currently: `"🔒 = @JwtAuth ... this port preserves behavior rather than changing it"` plus the "one deliberate divergence" / "label and kanban-column ... remain unguarded" sentences — the latter is no longer true and must go) with:

```markdown
🔒 = `@JwtAuth` (Nest `@UseGuards(JwtAuthGuard)`). **A valid JWT proves identity, not
authorization.** Every project-scoped action additionally goes through
`ProjectAccessService` (`com.kanban.modules.project.ProjectAccessService`):
`ensureRole(projectId, userId, minRole)` for path-scoped resources,
`ensureTaskRole(taskId, userId, minRole)` / `ensureColumnRole(columnId, userId, minRole)`
for task- and column-scoped ones (each masks a non-member behind the same 404 as an
unknown task/column). Routes with a project id in the path instead use `@JwtAuth` +
`@RequireProjectRole(value, param)`, enforced globally by `ProjectRoleInterceptor`
(`modules/project/guards/`, registered in `WebMvcConfig` right after `JwtAuthInterceptor`).
Passing `userId` in only to emit an activity/notification event is not an access check.
`GET /board/:projectId` is guarded here beyond plain JWT — it also requires
`ProjectRole.VIEWER` via `@RequireProjectRole`.

**Roles:** project-scoped `ProjectRole` (`OWNER` > `ADMIN` > `MEMBER` > `VIEWER`, see
`ProjectRole.rank()`). Reads need any membership; task/comment mutations need `MEMBER`;
project/team/column/member/invitation management needs `ADMIN`; deleting the project,
touching owner/admin roles, and inviting admins need `OWNER`. `User.role` / `UserRole`
remains descriptive metadata — don't gate on it.

**404 masking:** a non-member gets a `404` identical to the unknown-resource case
(`Project with id "..." not found`, or the task/column-flavored equivalent) from every
project-scoped route (anti-enumeration); a member below the required role gets
`403 This action requires at least <role> role`. Don't reintroduce 403 for non-members.

**Invitations** (`com.kanban.modules.invitation`): tokens are 32 random bytes
(`SecureRandom`) returned once at creation; only the SHA-256 hex hash is stored
(`token_hash`, dropped by `ProjectInvitation`'s JSON serialization). 7-day expiry,
revocable, single-use, and acceptance requires the authed user's email to match — all
invalid-token failures return the same generic 400. No mailer exists: delivery is an
out-of-band invite link + in-app notification (`EventsService`/`EventsGateway`) for
existing users.

**WS rooms:** clients join `project:{id}` via the `board:join` Socket.IO message, which
verifies membership first (`EventsGateway.handleBoardJoin`); `user:{id}` rooms come from
the JWT handshake. Never `client.join` before the membership check.
```

In §4 "Test mapping", update the final `mvn test` count in the "Run:" line to the actual total once this task's new test cases land (baseline 127 per the constraints doc, plus whatever `TaskServiceTest`/`EventsGatewayTest`/`SubscriptionControllerTest`/`CommentServiceTest` gained in Tasks 11 and 13).

- [ ] **Step 2: Full verification sweep**

```bash
mvn -q compile && mvn test
```

(no linter is configured for this port — skip.) Expected: all green. Then a manual security spot-check against the dev server (`mvn spring-boot:run`; Swagger UI at `http://localhost:1996/api/docs`, per `application.yml`'s `server.port`):

1. No token → `GET /api/projects` → 401.
2. User A creates a project; user B (no membership) → `GET /api/projects/<idA>` → **404** (not 403).
3. A invites B as `viewer` (`POST /api/projects/<idA>/invitations`) → response contains a 64-char `token`; `GET /api/projects/<idA>/invitations` response contains **no** `token_hash`.
4. B accepts with the token (`POST /api/invitations/accept`) → 200 + project; second accept → 400.
5. B (viewer) → `PATCH /api/tasks/<taskInA>` → 403; `GET /api/board/<idA>` → 200.
6. A demoting themself → 403 (self-change); A removing themself as sole owner → 409.

- [ ] **Step 3: Commit**

```bash
git add MIGRATION.md
git commit -m "docs: document RBAC model, 404 masking, and invitation conventions

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Then stop: pushing and opening the PR (`gh pr create --base main`) requires the user's explicit go-ahead per `.claude/rules/git.md`.

---

## Out of scope (explicitly deferred — do not gold-plate)

- **Frontend work** (projects list, member management UI, `usePermissions()`, 403 states) — separate plan in the frontend repo.
- **Email delivery** of invitations (no mailer in this repo; the one-time-token + invite-link flow is the contract the frontend builds on).
- **Project-scoping labels** (global table today; needs its own schema migration).
- **Validating that task assignees are project members** (data-integrity follow-up, not an authorization hole).
- **Presence query scoping** (`GET /presence?userIds=` remains visible to any authenticated user).
- **Real-DB e2e tests** (this port's tests are plain JUnit 5 + Mockito unit tests against mocked repositories, no database required; the permission matrix is covered at the service level, per repo convention — see `MIGRATION.md` §4).
- The repo-wide "Known Decisions" items (response envelope, versioning, helmet/CORS) that the original brief deferred — this port has no separate tracker for them; nothing to update here beyond `MIGRATION.md` §6's existing compatibility notes.

## Appendix: Source Spec & Design Decisions

**Feature (from the user's brief):** Project membership & RBAC — owner/admin/member/viewer roles; invite/remove members and change roles; permissions controlling project editing, task creation/movement, member management, and visibility. Backend scope: membership + invitation APIs, central authorization service, project-scoped queries, invitation acceptance/expiry, role-change rules, last-owner protection, authorization for HTTP **and** Socket.IO rooms. Security: no cross-project enumeration, hashed invitation tokens, expiry/revocation, no self-escalation, last-owner protection, authorized socket joins, no leaking that private projects exist. The server — never the UI — enforces every permission.

**Design decisions made against the existing codebase:**
1. `ProjectMember`/`ProjectRole` (owner/admin/member) already existed in this port's initial NestJS-to-Java translation — this plan extends them (adds `viewer`) rather than re-modeling; `project_members` already has the composite primary key `(project_id, user_id)` (`ProjectMemberId`), satisfying the uniqueness requirement.
2. Non-membership → **404** (masks existence), insufficient role → **403**. This changes the port's previous behavior (a plain 403 for non-members) deliberately, per the anti-enumeration requirement.
3. Central policy is a service (`ProjectAccessService`) plus an interceptor (`ProjectRoleInterceptor`, this port's analogue of a Nest guard) for path-scoped routes, because task/comment/column routes have no project id in the path — resolution (task → column → project, or column → project) must hit the DB, which services do idiomatically in this codebase.
4. Invitations return the raw token once (create response) because the repo has no mailer; in-app notification (`EventsService`/`EventsGateway`) covers existing users; email-binding on acceptance keeps a leaked link useless to others.
5. Owner is never grantable by invitation; admins manage member/viewer; owners manage everything; no self role change; `409` for anything that would leave zero owners.
6. Legacy try/catch blocks that leak driver error messages are not copied into new code; methods rewritten by this plan throw typed exceptions directly, per the "never leak internals" rule in `.superpowers/sdd/2026-08-22-project-membership-rbac/constraints.md` (this port has no `CLAUDE.md` to carry that rule).
