# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Kanban board backend API — Java 21 / Spring Boot 3.5 port of a NestJS service. Serves the **same HTTP API** (`/api/...`, port 1996) against the **same PostgreSQL schema** (Supabase), so the existing frontend keeps working. **`MIGRATION.md` is the source of truth for Nest parity** (framework mapping, route table, validation engine, test mapping, deliberate compatibility differences) — read it before changing any wire-visible behavior. `2026-08-22-project-membership-rbac.md` is the (in-progress) RBAC rollout plan; a reference implementation lives on branch `feat/project-membership-rbac`.

## Working Style

Behavioral guidelines to reduce common LLM coding mistakes. **Tradeoff:** they bias toward caution over speed — for trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

## Build Tool

**Maven only** (`pom.xml`, `spring-boot-starter-parent` 3.5.x, `java.version` 21). No Gradle, no Lombok, no linter/formatter plugin — match the surrounding style by hand (2-space indent, constructor injection, `final` fields).

## Commands

- **Run (dev):** `mvn spring-boot:run` — needs a `.env` (copy `.env.example`) with at least `JWT_SECRET` (no default → boot fails) and Postgres vars
- **Compile:** `mvn -q compile`
- **Package & run jar:** `mvn -DskipTests package && java -jar target/kanban-backend-1.0.0.jar`
- **All tests:** `mvn test` (JUnit 5 + Mockito + AssertJ; no database needed)
- **One class:** `mvn test -Dtest=TaskServiceTest`
- **One method:** `mvn test -Dtest='TaskServiceTest#methodName'`
- **Offline (no artifact downloads):** add `-o` (e.g. `mvn -q -o test -Dtest=AppControllerTest`)
- Swagger UI: `http://localhost:1996/api/docs` (JSON at `/api/docs-json`); Socket.IO: `http://localhost:1997`

## Architecture

- **Bootstrap:** `KanbanApplication` (`@SpringBootApplication @EnableAsync`). `config/WebMvcConfig` = Nest `main.ts`: global `/api` prefix (added to every `com.kanban` controller — **never write `/api` in a mapping**), CORS `origin:*`, the `JwtAuthInterceptor`, and the custom argument resolvers (`ParamResolver`, `ValidatedBodyResolver`, `ValidatedQueryResolver`, `CurrentUserResolver`).
- **Config:** `application.yml` imports `.env` from the working directory (`spring.config.import: optional:file:.env[.properties]`); env var names are identical to the Nest app (`POSTGRES_*`, `POSTGRES_SSLMODE`, `DB_POOL_SIZE`, `JWT_SECRET`, `JWT_EXPIRES_IN`, `REFRESH_TOKEN_EXPIRES_IN`, `NODE_ENV`, `PORT`, `SOCKET_IO_PORT`, `SOCKET_IO_ENABLED`, `FLYWAY_*`). App settings bind to the `AppProperties` record (`app.*`). `SwaggerEnvironmentPostProcessor` (registered in `META-INF/spring.factories`) disables springdoc when `NODE_ENV=production`.
- **Package layout (`src/main/java/com/kanban`):**
  - `config/` — MVC, Jackson (JS-style `toISOString()` dates), async executor (`eventExecutor`), OpenAPI bean, properties.
  - `common/exception` — Nest `HttpException` family (`NotFound/Forbidden/Conflict/BadRequest/Unauthorized/InternalServerError`) + `GlobalExceptionHandler` (`@RestControllerAdvice`) that renders byte-identical Nest bodies (unknown route → `Cannot GET /api/x`, unhandled → `{ statusCode: 500, message: "Internal server error" }`).
  - `common/validation` — a **re-implementation of class-validator/class-transformer**, not Jakarta Bean Validation: `ValidatedDto` base class, `@ValidatedBody` / `@ValidatedQuery` resolvers, constraint annotations (`@IsString`, `@IsUUID`, `@IsOptional`, `@IsEnum`, `@Min/@Max`, `@ArrayNotEmpty`, …), `@TypeNumber`, `@TransformWith(XTransformer.class)`, `WireEnum`.
  - `common/pipes` — `@Param(value, pipe = Param.Pipe.UUID | INT | PROJECT_ID)` ports of `ParseUUIDPipe` / `ParseIntPipe` / `ParseProjectIdPipe` (identical error messages).
  - `common/api` — `ApiListResponse<T>`, `PaginatedResponse<T>` + `PaginationMeta`. `common/json/Json.map(k, v, …)` builds ordered maps (JS object literals). `common/util` — `SanitizeHtml` (jsoup), `ParseMentions`, `DurationParser` (`ms` grammar), `Dates`, `PgErrors`.
  - `common/events` — `EventBus` (`SpringEventBus` → `ApplicationEventPublisher`). Listeners are `@Async("eventExecutor") @EventListener` methods on typed payload records (`modules/notification/NotificationListener`, `modules/activity/ActivityListener`) — fire-and-forget like Nest's `EventEmitter2`; each listener swallows and logs its own errors.
  - `modules/<feature>/` — one package per Nest module: entity, `XRepository` (Spring Data), `XService`, `XController`, `dto/`, optional `events/`. `modules/events` is the Socket.IO gateway (`EventsGateway`, `EventsService`, `WsJwtGuard`, `socket/NettySocketIoServer` behind `SocketServer`/`SocketClient` adapters). `modules/board` is read-only aggregation (no entity); `modules/mention` is service-only.
- **Socket.IO runs on its own port (1997, netty-socketio)** — Tomcat cannot host the Socket.IO protocol. Event names, payloads, rooms (`user:<id>`, `project:<id>`) and the `token:refresh` flow match Nest; see `MIGRATION.md` §6. Sockets auto-join their projects' rooms on connect (presence); `board:join` / `board:leave` (`{ projectId }`) add explicit subscriptions, and `board:join` runs `ProjectAccessService.ensureRole(VIEWER)` **before** the join — any failure gets the same generic `board:join:error` (no membership oracle over WS). Queries: `docs/queries/events.md`; **frontend contract (every WS message, payloads, React/React Query reference): `docs/api-contracts/socket-events.md` — update it in the same change as any WS-visible behavior.**
- **Schema:** Flyway (`src/main/resources/db/migration`). `V1__baseline_schema.sql` = the TypeORM-synchronized schema + the position stored procedures/trigger (`fn_set_ticket_id`, `fn_move_task`, `fn_reorder_task`, `fn_reorder_subtask`). `ddl-auto: none`. New changes go in `V2__*.sql`, `V3__*.sql`, … as raw SQL — never edit an applied file. Against a DB the Nest app created, start once with `FLYWAY_BASELINE_ON_MIGRATE=true`.
- **Tests:** `src/test/java/com/kanban/...` mirrors main. Plain unit tests with `mock(XRepository.class)` + constructor-built services (no Spring context); test doubles in `testing/` (`RecordingEventBus`, `FakeSocketClient`). `WebLayerTest` is the only MVC-layer test: `@WebMvcTest` + `@Import({WebMvcConfig, JacksonConfig, GlobalExceptionHandler, JwtAuthInterceptor, …})` + `@MockitoBean` services, pinning the wire format (validation bodies, 401 body, pipe 400 body, unknown-route 404). No DB/integration tests.

## API Conventions

- **Routing:** `@RestController` + plural-noun, kebab-case paths (`@RequestMapping("/tasks")` or full paths on methods for nested resources: `/tasks/{taskId}/comments`, `/projects/{projectId}/teams`). Multi-word segments use kebab-case (`unread-count`, `by-ticket/{ticketId}`, `me/projects`). Model relationship changes as sub-resource `POST`/`DELETE` (`/{id}/assignees`, `/{id}/labels`, `/{id}/members`). (`/board` is the one legacy singular controller — don't copy it.)
- **Validation:** every request field lives on a `ValidatedDto` subclass with **public snake_case fields**, bound via `@ValidatedBody` (JSON) or `@ValidatedQuery` (query string). Use the `common/validation` annotations — **not** `@Valid`/`@RequestBody`/`@RequestParam`/Jakarta constraints (they'd produce different error bodies). Whitelist/forbid-non-whitelisted/transform semantics are built in; never validate request shapes manually in controllers or services. Query DTOs need an explicit parse transform (`@TypeNumber` or `@TransformWith(ParseIntTransformer.class)`) and use field initializers as defaults. `dto.has("field")` distinguishes absent from explicit `null` (the TS `!== undefined` checks) — use it for PATCH semantics.
- **Path params:** `@Param(value = "id", pipe = Param.Pipe.UUID)` for UUID ids; `Pipe.INT` for integer ids (label, column, team); `Pipe.PROJECT_ID` for project ids. Never `@PathVariable` directly.
- **Swagger (springdoc):** `@Tag` per controller; `@Operation` + `@ApiResponse` per route; `@Parameter(name=…)` for path params; `@SecurityRequirement(name = "bearer")` next to every `@JwtAuth`; `@Schema` on every DTO field.
- **Status codes:** Spring defaults every method to 200, so **every creating `POST` needs `@ResponseStatus(HttpStatus.CREATED)`** (Nest gave 201 for free). `DELETE` with no body → `@ResponseStatus(HttpStatus.NO_CONTENT)` + `void`. Some legacy DELETEs return 200 — don't copy them.
- **Pagination:** offset-based — `page` (1-based, default 1) + `limit` (default 20, max 100) → `PageRequest.of(page - 1, limit, sort)`. No cursor pagination.

## Wire Format

- **snake_case everywhere on the wire:** JSON request/response keys, DTO fields, and `@Column(name = …)` names (`full_name`, `access_token`, `column_id`, `due_date`, `ticket_id`). Java fields inside entities are camelCase and mapped explicitly. Do not introduce camelCase request/response fields. (Known camelCase surfaces: Socket.IO event payloads in `modules/events` and `BoardQueryDto` — don't extend them.)
- **Serialization is explicit, not reflective:** entities are never returned directly — each has a `toJson()` that returns an ordered `Map<String, Object>` (built with `Json.map`) reproducing TypeORM's `toJSON()`: hidden columns stay hidden (`password_hash`, `Project.ticket_counter/created_by`, `Comment.author_id`, …) and a relation appears **only when the query loaded it** (`Task.toJson(Set<String> relations)` receives the same relation set the TS `relations: [...]` used). Add new fields to `toJson()`, not via Jackson annotations on the entity.
- **Enums:** implement `WireEnum` with a snake_case `value()` marked `@JsonValue`, plus a `WireEnumConverter` subclass (`@Converter`) applied on the entity column with `@Convert(converter = XConverter.class)` — see `TaskStatus`/`TaskStatusConverter`/`Task.status`.
- Dates are `Instant`, serialized as `2025-01-01T00:00:00.000Z` by `JacksonConfig`.

## Responses & Error Handling

- **No global response envelope.** Three shapes coexist — match the module you're editing: list endpoints use `ApiListResponse.ok(list)` = `{ data, status, success, message? }`; single-entity/board endpoints return the raw `toJson()` map / response record; paginated comments return `PaginatedResponse` = `{ data, meta: { page, limit, total, totalPages } }` — reuse that exact shape for new paginated lists.
- **Throw, don't catch in controllers.** Services throw `com.kanban.common.exception.*`; controllers never try/catch. Mapping: `NotFoundException` 404, `ForbiddenException` 403 (ownership/role), `ConflictException` 409, `BadRequestException` 400, `UnauthorizedException` 401. Constructor arg follows Nest's `createBody` rules: a `String` → `{ message, error: <status text>, statusCode }`; a `Json.map(...)` → sent verbatim (use it to pin an exact body).
- **Never leak internals to clients:** don't put `PgErrors.message(e)` / `e.getMessage()` in a response body — it exposes SQL/constraint text. `log.error(...)` and throw a generic message. Much existing code (`internal("Failed to …", e)`) still leaks — don't copy it.
- If a service keeps a try/catch that re-throws, guard on `catch (HttpException e) { throw e; }` (not a hand-listed set of subclasses) so a new throw isn't downgraded to 500. Cross-cutting error/response shaping belongs in `GlobalExceptionHandler`, not per-controller.
- Detect DB constraint failures with `PgErrors.isCode(e, PgErrors.UNIQUE_VIOLATION)` / `PgErrors.constraint(e)` (walks the cause chain) — follow `ProjectService.create`.

## Authentication & Authorization

- **Auth is opt-in per route** via `@JwtAuth` (method or class level) + `@SecurityRequirement(name = "bearer")`. `JwtAuthInterceptor` verifies the bearer token (auth0 `java-jwt`, HS256), reloads the live `User` (rejecting inactive/missing users) and stores it on the request. There is NO global guard, so any unannotated route is fully public. Guard every mutating route and every sensitive read. (Since JAV-21 every route except `GET /` and the public auth routes — register, login, refresh, logout — carries `@JwtAuth`; keep it that way.)
- **A valid JWT proves identity, not authorization.** For project/task/comment/team operations also enforce access via `ProjectAccessService` (`modules/project`): `ensureRole(projectId, userId, minRole)` for project-scoped actions, `ensureTaskRole`/`ensureColumnRole` for task/column-scoped ones, or resource ownership (comment `authorId.equals(userId)`). Actor ids on gated service methods are **required** — a method that does `if (actorId != null) { … }` only to emit events (much of `TaskService`) is *not* performing an access check. The HTTP rollout is complete (JAV-19/20/21): project, board and team-read routes gate through `@RequireProjectRole`; column, task, comment, subscription and activity routes gate through `ensureColumnRole`/`ensureTaskRole`; `GET /users/{id}/projects` is self-only (403); Socket.IO `board:join` gates through `ensureRole(VIEWER)` before joining `project:<id>` (JAV-22). Labels stay global (no `project_id`) — JWT only. The RBAC plan document tracks what remains (MIGRATION.md, JAV-23).
- **Roles:** project-scoped `ProjectRole` (`OWNER > ADMIN > MEMBER > VIEWER`, compare with `rank()`) via `ProjectAccessService` is the only authorization gate. Run the gate **before** any resource lookup: non-members get a masked 404 indistinguishable from a missing resource (anti-enumeration); members below the required role get 403. `User.role`/`UserRole` is descriptive metadata — don't gate on it.
- Read the caller with `@CurrentUser("id") String userId` (or `@CurrentUser User user`).
- **Secrets:** never expose `password_hash` (`User.toJson()` omits it; load it only via the dedicated `UserService` lookup). Hash with `BCryptPasswordEncoder` (`$2b$`, cost 10 — hashes are compatible with the Nest app). Refresh tokens are opaque random values stored as sha256 hashes, rotated with reuse-detection on every refresh — never issue a JWT as the refresh token. `JWT_SECRET` shared with the Nest app keeps tokens interchangeable.

## Data Layer (Spring Data JPA)

- **Access:** constructor-injected `XRepository extends JpaRepository<Entity, Id>`. Derived queries / `@Query` JPQL for what the repository API can express; a `*Queries` interface + `Jpa*Queries` `@Repository` impl (`EntityManager` native SQL) only for joins/aggregates it can't (`ProjectAccessQueries`, `BoardQueries`); `TaskPositionFunctions` (`JpaTaskPositionFunctions`) is the only place the position stored procedures are called.
- **Relations:** `open-in-view` is **off** and relations are `FetchType.LAZY` — touching an unloaded relation outside a transaction throws. Load explicitly per query with `@EntityGraph(attributePaths = …)` on a `@Query` method (`CommentRepository.findByIdWithAuthor`), never inside loops. Mutations follow save-then-refetch (`save`, then `findOneById()` re-queries the relation graph the response needs).
- **Transactions:** wrap any multi-row write that must stay consistent (membership changes) — `@Transactional` on the service method or `TransactionTemplate` as in `ProjectService.create`. Repository `@Modifying @Query` methods carry their own `@Transactional` (existing pattern).
- **Bound collection queries:** paginate (`PageRequest` + `Page<T>`, with an explicit `countQuery`) and select needed columns; don't return unbounded full relation graphs.
- **Ids are `String`** (uuid columns; `@UuidGenerator` + `columnDefinition = "uuid"`). The JDBC URL carries `stringtype=unspecified` so Postgres infers `uuid`/enum types from string parameters — don't remove it.
- **Document the raw SQL:** whenever you add or change an API, controller, or service that touches PostgreSQL, create or update `docs/queries/<feature>.md` (one file per `modules/<feature>/`) **in the same change**, listing every query that implementation runs as raw PostgreSQL — including the SQL Spring Data derives for you (derived finders, JPQL `@Query`, `@EntityGraph` joins), not just native queries and stored-procedure calls. Per query: the endpoint/feature it serves, one line on what it does, the raw SQL, and its parameters with example values where useful. Purpose: developers can see exactly what hits the database, run and verify it in `psql`, debug service behavior, and review correctness/performance without translating ORM code into SQL.

## Shared Utilities, Logging & Tests

- **Logging:** one `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` (SLF4J) per service/gateway/listener. No `System.out`/`printStackTrace`.
- **HTML sanitization:** sanitize user-supplied HTML at the DTO boundary with `@TransformWith(SanitizeTransformer.class)` (wraps `SanitizeHtml`). Apply to any new field rendered as HTML (only comment content today).
- **Module shape:** follow the neighboring module; aggregation/gateway modules omit pieces intentionally.
- **Tests:** add `XServiceTest` next to new services — plain JUnit 5, `mock(...)` repositories, `RecordingEventBus` for event assertions, AssertJ (`assertThat`) + `@DisplayName` porting the spec name. Controller/wire-format changes go in `WebLayerTest` (add the controller to `@WebMvcTest(controllers = …)` and `@MockitoBean` its service). DTOs used in tests get a convenience constructor that calls `with("field")` to mark keys present.

## Skills (`.claude/skills/`)

Auto-load by description. All three are **generic Spring references** — where their advice conflicts with this file, **this file wins**:

- **`layered-architecture`** — controller/service/repository separation. Conflicts: this repo returns entity `toJson()` maps (not mapper-built response DTOs), validates with `@ValidatedBody` (not `@Valid`), and uses no Lombok.
- **`transactional-patterns`** — `@Transactional` propagation/isolation/read-only guidance. Conflict: existing `@Modifying` repository methods carry `@Transactional`; don't refactor them, but put new multi-step writes on service methods.
- **`spring-ai-integration`** — Spring AI `ChatClient`/RAG patterns; no AI code in this repo today.

## Known Decisions (not yet settled)

Contract/scaffolding choices, not existing conventions — confirm before relying on them: unifying the response envelope; API versioning; standardizing DELETE on 204; fail-fast env validation beyond `JWT_SECRET`; security hardening (CORS allowlist replacing `origin:*`, body-size limit, guarding the remaining public routes); the full RBAC rollout in `2026-08-22-project-membership-rbac.md`.

## Gotchas

- `.claude/worktrees/feat-project-membership-rbac/` is a git worktree checkout of another branch — exclude it from searches and never edit it from here.
- Supabase session pooler caps connections per DB user; keep `DB_POOL_SIZE` ≤ 3–5 or use the transaction pooler (port 6543).
- `README.md`/`MIGRATION.md` reference the original Nest sources as `../src` and `../sql`; those paths are not present in this checkout.
