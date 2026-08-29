# NestJS → Spring Boot migration mapping

This document records how every part of the NestJS/TypeScript service
(`../src`) was ported to the Spring Boot application in this directory, how the
TypeScript tests map to Java tests, and the (few) unavoidable compatibility
differences. **The Nest implementation and its tests are the source of truth**;
where behavior was ambiguous the Java code reproduces what the Nest code does,
including its quirks.

## 1. Runtime & framework mapping

| Concern | NestJS | Spring Boot |
| --- | --- | --- |
| Bootstrap, port 1996, global `/api` prefix | `main.ts` | `KanbanApplication`, `application.yml` (`server.port`), `WebMvcConfig.configurePathMatch` |
| CORS `origin: '*'`, no credentials | `app.enableCors` | `WebMvcConfig.addCorsMappings` |
| Config / `.env` | `@nestjs/config` + dotenv | `spring.config.import=optional:file:.env[.properties]`, `AppProperties` |
| Swagger at `/api/docs` unless `NODE_ENV=production` | `@nestjs/swagger` | springdoc (`/api/docs`, JSON at `/api/docs-json`), toggled by `SwaggerEnvironmentPostProcessor` |
| Global `ValidationPipe({ whitelist, forbidNonWhitelisted, transform })` | class-validator / class-transformer | `common/validation/ClassValidator` + `@ValidatedBody` / `@ValidatedQuery` argument resolvers (see §3) |
| `ParseUUIDPipe`, `ParseIntPipe`, `ParseProjectIdPipe` | `@Param('id', Pipe)` | `@Param(value, pipe = UUID / INT / PROJECT_ID)` + `ParamResolver` / `Pipes` (identical messages) |
| `HttpException` family + default filter | `@nestjs/common` | `common/exception/*` — same `createBody` rules; `GlobalExceptionHandler` renders identical bodies, unknown routes → `Cannot GET /api/x`, unhandled → `{ statusCode: 500, message: "Internal server error" }` |
| `@UseGuards(JwtAuthGuard)` (opt-in per route) | passport-jwt | `@JwtAuth` (method or class) + `JwtAuthInterceptor`; failure body `{ message: "Unauthorized", statusCode: 401 }` |
| `@CurrentUser()` / `@CurrentUser('id')` | param decorator | `@CurrentUser` / `@CurrentUser("id")` + `CurrentUserResolver` |
| `JwtService` (HS256, `expiresIn` ms-grammar) | `@nestjs/jwt` | `JwtService` (auth0 java-jwt) + `DurationParser`; same claims `sub`, `email`, `role`, `iat`, `exp` — tokens are interchangeable between the two apps when `JWT_SECRET` matches |
| bcryptjs (`$2b$`, cost 10) | bcryptjs | `BCryptPasswordEncoder($2B, 10)` — hashes verify across both apps |
| TypeORM entities / repositories | `@nestjs/typeorm` | JPA entities + Spring Data repositories (one per entity, same table/column names) |
| `DataSource.query('SELECT fn_move_task…')` | raw SQL | `TaskPositionFunctions` (`JpaTaskPositionFunctions`) |
| Raw project lookups in `ProjectAccessService` | query builder | `ProjectAccessQueries` (`JpaProjectAccessQueries`) |
| Board count / ROW_NUMBER queries | query builder | `BoardQueries` (`JpaBoardQueries`) |
| `EventEmitter2.emit` + `@OnEvent` | `@nestjs/event-emitter` | `EventBus` (`SpringEventBus` → `ApplicationEventPublisher`) + `@Async @EventListener` on typed payloads (`eventExecutor` pool). Fire-and-forget, like Nest |
| Socket.IO gateway | `@nestjs/platform-socket.io` | netty-socketio (`NettySocketIoServer`) behind `SocketServer`/`SocketClient` adapters; `EventsGateway`, `EventsService`, `WsJwtGuard` ported 1:1 |
| Migrations (`src/migrations`, never executed — `synchronize` was on) | TypeORM | Flyway `V1__baseline_schema.sql` (schema + stored procedures + trigger). `synchronize` has no equivalent and is intentionally not reproduced |
| Logging | `Logger` per class | SLF4J `Logger` per class, same messages |
| Seed script (`src/database/seed.ts`) | ts-node script | not ported (dev tooling, not part of the API); `sql/*.sql` seeds still apply |

## 2. Module-by-module mapping

Every Nest module lives in `com.kanban.modules.<feature>` with the same shape
(entity, repository, service, controller, dto). Routes, methods, status codes,
guards and response shapes are identical.

| Nest module | Java package | Routes |
| --- | --- | --- |
| `app.controller` | `com.kanban.AppController` | `GET /api` → `Hello World!` |
| `auth` | `modules.auth` | `POST /auth/register` 201, `POST /auth/login` 200, `POST /auth/refresh` 200, `POST /auth/logout` 200, `POST /auth/logout-all` 200 🔒, `GET /auth/me` 🔒 |
| `user` | `modules.user` | `GET /users`, `GET /users/me/projects` 🔒, `GET /users/:id`, `GET /users/:id/projects` |
| `project` (+ `ProjectAccessService`) | `modules.project` | `POST /projects` 🔒 201, `GET /projects`, `GET /projects/:id`, `PATCH /projects/:id`, `DELETE /projects/:id` 200, `GET /projects/:id/members`, `POST /projects/:id/members` 🔒 201, `DELETE /projects/:id/members` 🔒 204 |
| `team` | `modules.team` | `projects/:projectId/teams` — `POST` 🔒 201, `GET`, `GET /:teamId`, `GET /:teamId/members`, `POST /:teamId/members` 🔒 201, `DELETE /:teamId/members/:userId` 🔒 204 |
| `kanban-column` | `modules.kanbancolumn` | `POST /columns` 201, `GET /columns`, `GET /columns/:id`, `PATCH /columns/:id`, `DELETE /columns/:id` 200 |
| `label` | `modules.label` | `POST /labels` 201, `GET /labels`, `GET /labels/:id`, `PATCH /labels/:id`, `DELETE /labels/:id` 200 |
| `task` | `modules.task` | `POST /tasks` 🔒 201, `GET /tasks`, `GET /tasks/by-ticket/:ticketId`, `GET /tasks/:id`, `PATCH /tasks/:id` 🔒, `PATCH /tasks/:id/reorder` 🔒, `PATCH /tasks/:id/move` 🔒, `DELETE /tasks/:id` 200, `POST /tasks/:id/subtasks` 🔒 201, `GET /tasks/:id/subtasks`, `PATCH /tasks/:id/subtasks/:subtaskId/reorder`, `POST/DELETE /tasks/:id/assignees` 🔒 (201/200), `POST/DELETE /tasks/:id/labels` 🔒 (201/200) |
| `board` | `modules.board` | `GET /board/:projectId?tasksPerColumn&assigneeId&priority&labelId&search` |
| `comment` | `modules.comment` | `POST /tasks/:taskId/comments` 🔒 201, `GET /tasks/:taskId/comments`, `PATCH /comments/:id` 🔒, `DELETE /comments/:id` 🔒 204 |
| `notification` (+ listener) | `modules.notification` | 🔒 `GET /notifications`, `GET /notifications/unread-count`, `PATCH /notifications/read`, `PATCH /notifications/read-all`, `DELETE /notifications/:id` 204 |
| `activity` (+ listener) | `modules.activity` | `GET /tasks/:taskId/activities` 🔒 |
| `subscription` | `modules.subscription` | 🔒 `POST /tasks/:taskId/subscription` 201, `DELETE …/subscription` 204, `GET …/subscription/me`, `GET /tasks/:taskId/subscribers` |
| `presence` | `modules.presence` | 🔒 `GET /presence?userIds=a,b`, `GET /presence/me` |
| `mention` | `modules.mention` | (service only) |
| `events` | `modules.events` | Socket.IO: `connection:established`, `connection:error`, `token:refresh` → `token:refresh:success` / `token:refresh:error`, `notification:new`, `presence:update`; rooms `user:<id>`, `project:<id>` |

🔒 = `@JwtAuth` (Nest `@UseGuards(JwtAuthGuard)`). Unguarded routes are unguarded
here too — that is the current contract (CLAUDE.md flags it as a known bug, but
this port preserves behavior rather than changing it).

### Response serialization

TypeORM's `toJSON()` behavior is reproduced by explicit `toJson(...)` methods on
the entities:

- hidden fields stay hidden (`password_hash`, `Project.ticket_counter/created_by`,
  `Comment.author_id`, `Notification.recipient_id/actor_id`, `Activity.task_id/actor_id`);
- a relation appears **only when the Nest query loaded it** (`Task.toJson(relations)`
  receives the same relation set the TS `relations: [...]` option used), and
  `Task.parent` follows the summary / `{ id }` / `null` / absent rules of `Task.toJSON()`;
- dates serialize as JS `toISOString()` (`2025-01-01T00:00:00.000Z`, `JacksonConfig`);
- the three response shapes (`ApiListResponse`, raw entity, `PaginatedResponse`) are
  kept per endpoint exactly as in Nest.

## 3. Validation engine (class-validator parity)

`ClassValidator` re-implements the subset of class-validator/class-transformer the
DTOs use, with the **same messages and ordering**:

- unknown keys → `property <k> should not exist` (reported first, payload order);
- per property in declaration order, constraints reported bottom-up (the TS
  decorator evaluation order), e.g. `password must be shorter than or equal to 72 characters`,
  `… longer than or equal to 8 characters`, `password must be a string`;
- `@IsOptional` skips null/absent; `each: true` constraints are skipped for
  non-arrays; `@IsNotEmpty` rejects only `''`/null/absent;
- transforms run only for present keys: `@TypeNumber` (= `@Type(() => Number)`),
  and `@TransformWith` classes porting each `@Transform` lambda (`DueDateTransformer`,
  `TeamIdTransformer`, `SanitizeTransformer`, `ParseIntTransformer`,
  `ParseIntIfStringTransformer`, `BooleanStringTransformer`, `UserIdsTransformer`);
- `ValidatedDto.has(key)` gives services the `undefined` vs `null` distinction
  the TS code relies on (`!== undefined` checks, `Object.assign` semantics);
- query DTOs get Express "simple" parsing (repeated keys → array) and field
  initializers act as class-property defaults.

`common/validation/UuidPatterns` copies the exact validator.js / Nest regexes;
`EmailValidator` ports validator.js `isEmail` default options.

## 4. Test mapping

| TypeScript spec | Java test | Cases |
| --- | --- | --- |
| `src/app.controller.spec.ts` | `AppControllerTest` | 1 |
| `test/app.e2e-spec.ts` (`GET /` → Hello World!) | `WebLayerTest.hello` (+ 8 wire-format contract tests) | 9 |
| `common/utils/parse-mentions.util.spec.ts` | `ParseMentionsTest` | 5 |
| `project/project-access.service.spec.ts` | `ProjectAccessServiceTest` | 21 (incl. parameterized role matrix) |
| `team/team.service.spec.ts` | `TeamServiceTest` | 6 |
| `task/task.service.spec.ts` | `TaskServiceTest` | 7 |
| `comment/comment.service.spec.ts` | `CommentServiceTest` | 3 |
| `mention/mention.service.spec.ts` | `MentionServiceTest` | 3 |
| `subscription/subscription.service.spec.ts` | `SubscriptionServiceTest` | 14 |
| `subscription/subscription.controller.spec.ts` | `SubscriptionControllerTest` | 5 |
| `events/events.gateway.spec.ts` (`EventsGateway`) | `EventsGatewayTest` | 12 |
| `events/events.gateway.spec.ts` (`EventsService`) | `EventsServiceTest` | 3 |
| `presence/presence.service.spec.ts` | `PresenceServiceTest` | 19 (incl. the concurrent-connect race) |
| `presence/presence.controller.spec.ts` | `PresenceControllerTest` | 2 |
| — (new) | `ClassValidatorTest`, `UtilsTest` | validation-message parity, durations, dates, sanitizer, email |

Mocks follow the specs one-to-one: TypeORM repository mocks → Mockito mocks of the
Spring Data repositories; `EventEmitter2` mock → `RecordingEventBus`
(`emittedOf(name)`); the socket.io `Socket` mock → `FakeSocketClient`; the
`server.to().emit()` / `server.in().socketsJoin()` mocks → a Mockito `SocketServer`.
Assertions on query-builder internals (`select('col.project_id', …)`,
`andWhere('user.is_active = true')`, `orIgnore()`) become assertions on the
equivalent adapter/repository method calls.

Run: `mvn test` (126 tests, no database required).

## 5. Database

`V1__baseline_schema.sql` = TypeORM-synchronized schema of all 13 entities
(enum type names as TypeORM generates them: `users_role_enum`, `tasks_status_enum`,
`tasks_priority_enum`, `notifications_type_enum`, plus the explicitly named
`project_role`, `task_subscription_source`, `task_activity_action`) + the content of
the never-run `src/migrations/*` (they are already reflected in the entities) + the
stored procedures/trigger from `sql/kanban_tasks.sql` that the services call.
Constraint names satisfy the checks the services perform on Postgres errors
(`*_pkey`, `UQ_projects_tag` contains `tag`, `UQ_team_members_user_id_project_id`
contains `user_id`).

Against an existing Nest-created database, start once with
`FLYWAY_BASELINE_ON_MIGRATE=true` (or apply the idempotent script manually).

JDBC URL detail: the datasource URL carries `stringtype=unspecified` so that the
`String`-typed uuid ids and the snake_case enum values (bound through JPA
`AttributeConverter`s) are inferred by PostgreSQL as `uuid` / the enum types instead
of `varchar` — the same untyped-parameter behavior node-postgres has.

## 6. Compatibility differences (unavoidable or deliberate)

1. **Socket.IO port.** Nest served Socket.IO on the HTTP port (1996). Tomcat cannot
   speak the Socket.IO/Engine.IO protocol, so the Java app runs netty-socketio on
   `SOCKET_IO_PORT` (default **1997**). Clients must point `WS_URL` there
   (`io('http://host:1997', { auth: { token } })`). Everything else — `auth.token`
   handshake, event names, payloads, rooms, `token:refresh` flow — is unchanged.
   Socket ids are UUIDs instead of socket.io's 20-char ids (opaque to clients).
   One timing nuance: a client that connects **without** an `auth` payload is
   rejected (`connection:error` + disconnect) after a ~2 s grace period instead of
   immediately, because netty-socketio only exposes the Socket.IO `auth` object once
   the CONNECT packet arrives. Clients that send `auth: { token }` (the documented
   contract) are validated immediately.
2. **`error` field text in 500/409 bodies.** The Nest services leak the driver
   message (`error: (error as Error).message`). The Java port keeps the field and the
   shape but the text is the PostgreSQL/JDBC message, which is worded differently
   from node-postgres. `statusCode` and `message` are identical.
3. **Malformed JSON bodies.** Both return 400 `{ message, error: "Bad Request", statusCode: 400 }`;
   the `message` is the parser's own text (Jackson vs. Node's `JSON.parse`).
4. **`due_date` parsing.** `new Date(string)` accepts many formats; the port accepts
   ISO-8601 date/date-time (with or without offset — no-offset values are read as UTC),
   epoch milliseconds and booleans. Other free-form strings are rejected with
   `due_date must be a Date instance` (Nest would accept some of them).
5. **`GET /api` content type.** Express sends `text/html; charset=utf-8`; the port
   sets the same content type explicitly.
6. **`req.ip` format.** Refresh tokens store `request.getRemoteAddr()` (e.g.
   `0:0:0:0:0:0:0:1`) where Express stored `::1` / `::ffff:127.0.0.1`. Informational
   column only.
7. **Schema management.** `synchronize` (auto-DDL) is replaced by Flyway; the DB
   trigger/functions that were applied by hand from `sql/` are now part of `V1`.
8. **Event delivery.** Listeners run on a small thread pool (`@Async`) instead of the
   Node event loop; ordering across different listeners is not guaranteed in either
   runtime, and each listener still swallows its own errors.
9. **Email validation** ports validator.js `isEmail` (default options); exotic
   edge cases (IPv6 literals in the domain, some quoted local parts) may differ.
10. **`ParseUUIDPipe`/`IsUUID` regexes** are copied verbatim, so `GET /users/me`
    still yields `400 Validation failed (uuid is expected)` exactly like Nest.
