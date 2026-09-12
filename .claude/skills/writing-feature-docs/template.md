# Feature documentation template

One `# <Feature name>` title, then the fifteen numbered headings below **exactly as written**
(`## 1. Feature Overview` … `## 15. Maintenance Guide`), in this order. Use the repository as the
only source of truth: do not invent endpoints, tables, events, constraints, indexes or behaviour
that are not implemented. Keep `Recommended improvement` items visibly separate from current
behaviour in every section. Repository queries are written out once, in section 12; every other
section links to them.

---

## 1. Feature Overview

Purpose, business requirements, the problem it solves, main use cases, assumptions, non-goals,
feature boundaries, relevant packages (`com.kanban.modules.<feature>`, shared `common/*` pieces).

## 2. Architecture

- High-level architecture and how the feature plugs into existing modules
- Controller → service → repository flow; Spring dependency-injection relationships
- Dependencies on other services/repositories (`ProjectAccessService`, `EventBus`, gateways)
- Synchronous vs asynchronous work
- Design decisions and trade-offs — the *why*

Where the feature uses them, explain in plain language: the Spring MVC request lifecycle,
custom annotations and interceptors (`@JwtAuth` / `JwtAuthInterceptor`, `@RequireProjectRole` /
`ProjectRoleInterceptor`, `@ValidatedBody`), application-event dispatch (`@EventListener`,
`@Async("eventExecutor")`), Socket.IO room behaviour, transaction-proxy behaviour.

Add a sequence or flow diagram (Mermaid) when it clarifies a non-obvious path.

## 3. API Design

Per endpoint: HTTP method; full route **with the `/api` prefix**; controller method;
authentication (`@JwtAuth`) and authorization (`@RequireProjectRole`, service-level
`ensureRole` / `ensureTaskRole` / `ensureColumnRole`, ownership checks); path and query params
with their pipes; request DTO and its validation annotations; response shape (`toJson()` map,
`ApiListResponse`, `PaginatedResponse`, record); success status; error responses.

Cover success, validation failure (`{ message: [...], error, statusCode }`), 401, 403, 404
(including the masked non-member 404), 409, with example request/response payloads (snake_case).

Show controller code as Java with Spring annotations:

```java
@PostMapping("/example")
@ResponseStatus(HttpStatus.CREATED)
@JwtAuth
@SecurityRequirement(name = "bearer")
public Map<String, Object> create(@ValidatedBody CreateExampleDto dto, @CurrentUser("id") String userId) {
  return exampleService.create(dto, userId).toJson();
}
```

## 4. Database Design

Tables and JPA entities involved; entity ↔ table mapping; relationships and join tables;
primary/foreign keys; unique and check constraints; enums (`CREATE TYPE` + `WireEnum` converter);
indexes; nullability; cascade/delete behaviour; soft-delete or archived state; timestamp
behaviour (`@CreationTimestamp(source = DB)` etc.); why the schema looks this way.

Relate Java entity fields → JPA annotations → PostgreSQL columns → repository queries.
Include an ER diagram (Mermaid `erDiagram`) when more than two tables interact.

## 5. Repository and Persistence Layer

Spring Data interfaces and what each method loads or writes; derived queries vs JPQL vs native SQL
(`*Queries` / `Jpa*Queries`); `@EntityGraph` use and lazy vs eager loading; repository-level
`@Transactional` on `@Modifying` methods; locking (`@Lock`, `FOR UPDATE`); batch operations;
`save()` vs `saveAndFlush()` and the merge-SELECT cost of assigned/composite ids.

Describe the mechanism here; the SQL itself lives in section 12 — link each method to its entry
there (`see §12, query 3`) instead of repeating the statement.

## 6. Service Logic

Main business flow; responsibilities; validation rules; state transitions; transaction
boundaries; event flow; repository interactions; what is returned (entity re-fetch after save,
`toJson()` relation sets).

Per major operation: happy path, edge cases, failure scenarios, retry, idempotency, concurrency,
rollback. For transactional methods: why `@Transactional` sits on the service, which reads and
writes share the transaction, what happens on exception, pessimistic vs optimistic locking,
remaining race conditions.

## 7. Event and Real-Time Flow

When the feature emits application or Socket.IO events: event record class; producer; payload;
publisher (`EventBus` → `SpringEventBus` → `ApplicationEventPublisher`); matching
`@EventListener` methods; async executor; DB-notification listener; WebSocket listener; recipient
filtering; Socket.IO event names; `user:<id>` / `project:<id>` rooms.

```text
Service → EventBus.emit(event) → SpringEventBus → ApplicationEventPublisher.publishEvent(event)
        → matching @EventListener methods → database persistence and/or WebSocket delivery
```

State that Spring dispatches by the event's **Java type**, not by a string name. For Socket.IO
handlers distinguish: listener registration on the backend, events emitted by the frontend,
replies to the originating socket, broadcasts to rooms. Link `docs/api-contracts/socket-events.md`
for payloads instead of restating them.

## 8. Security Considerations

JWT authentication; interceptors; custom authorization annotations; project-membership checks and
the role hierarchy (`OWNER > ADMIN > MEMBER > VIEWER`); input validation; ownership checks;
anti-enumeration (masked 404); parameter binding vs SQL injection; sensitive data
(`password_hash`, `token_hash`); invitation tokens; rate limiting; audit logging; WebSocket
authentication and room authorization.

Two explicit lists: **Implemented protections** and **Recommended improvement**.

## 9. Performance Considerations

Query optimisation; index strategy; N+1 prevention; `@EntityGraph`; pagination; caching;
transaction and flush costs; locking; async processing; WebSocket fan-out; scalability
bottlenecks; expected query complexity. Call out every extra query a service method performs and
whether it is necessary.

## 10. Error Handling

Custom exception types (`com.kanban.common.exception.*`) and status codes; error-body shapes
(`createBody` rules, `Json.map` bodies sent verbatim); `GlobalExceptionHandler`; validation error
formatting; logging strategy; async listener error handling (swallow and log); WebSocket error
events; generic messages used to prevent information disclosure; monitoring notes. Which
exceptions roll back a transaction; which async failures are caught and logged.

## 11. Testing Strategy

Unit tests (plain JUnit 5 + Mockito + AssertJ, mocked repositories), web-layer tests
(`WebLayerTest`, `@WebMvcTest`), test doubles (`RecordingEventBus`, `FakeSocketClient`),
absence of DB/integration/E2E tests and why. Scenarios to cover **when the feature has them**:
happy paths, validation failures, 401, 403, masked 404, conflicts, rollback, concurrency, event
emission, listener behaviour, notification persistence, WebSocket join/leave. A scenario the
feature cannot have (no events, no sockets, no project gate) gets one line: not applicable, and why.

Two explicit lists: **Covered today** (name the test classes/methods) and **Missing**.

## 12. Raw PostgreSQL Queries

**Schema definitions:** the relevant `CREATE TYPE` / `CREATE TABLE` statements, keys, foreign
keys, unique/check constraints, defaults — copied from the Flyway migrations.

**Indexes:** each `CREATE INDEX` / `CREATE UNIQUE INDEX`, the rationale, and the queries it serves.

**Queries:** every statement the feature runs — SELECT / INSERT / UPDATE / DELETE / JOIN /
aggregate / conditional / locking — numbered, each with purpose, parameters, expected complexity,
related indexes, concurrency implications. This is the **only** place queries are written out;
sections 5, 6 and 9 link here by number. For each repository method give (1) the method, (2) the
equivalent JPQL — or, for `findAll` / `findById` / `save*` / `deleteById`, the Spring Data CRUD
operation it maps to — and (3) the approximate Hibernate SQL, saying it is approximate, never
byte-for-byte runtime SQL. Reuse and link `docs/queries/<module>.md` where it exists.

```java
List<Entity> findByProjectIdInAndIsArchivedFalseOrderByPositionAsc(List<String> projectIds);
```

```sql
-- approximate
SELECT ... FROM entities WHERE project_id IN (...) AND is_archived = false ORDER BY position ASC;
```

## 13. Configuration and Operations

Relevant `application.yml` settings, PostgreSQL connection settings, Flyway, async executor,
Socket.IO, environment variables (names only — **no secrets or real values**), logging.
Operational notes: applying migrations, starting the app, running tests, diagnosing failed async
events, diagnosing WebSocket connection/room issues. Use `yaml` and `bash` blocks.

## 14. Known Limitations and Future Improvements

Known limitations, concurrency risks, missing constraints/indexes/tests, technical debt,
architectural / reliability / scalability / observability improvements, ideas for later
iterations. Every item is a bullet carrying two bold tags on separate lines, as plain Markdown
(never inside a code fence — Notion stops rendering links and inline code there):

- **Current implementation:** what the code does today, with the class or file.
  **Recommended improvement:** the change, and what it buys.

Never present a recommendation as already implemented.

## 15. Maintenance Guide

How to: add an endpoint; add a repository query (and its `docs/queries` entry); add an event
type and `@EventListener`; add a Flyway migration (`V<n>__*.sql`, never edit an applied one);
add a notification type (enum + `V*` migration for the PostgreSQL enum); change WebSocket room
behaviour (and the API contract); which tests to update when behaviour changes; common mistakes.

Framework pitfalls to mention where relevant: Spring self-invocation bypassing `@Transactional`;
lazy loading outside a persistence context (`open-in-view` is off); wrong JPA relationship paths;
missing Flyway enum migrations; publishing events before commit; async listener exceptions never
reaching the request; WebSocket clients staying in stale rooms.

---

## Output requirements

- Clean Markdown for Notion: `#`/`##`/`###` headings, tables, fenced `java` / `sql` / `yaml` /
  `bash` blocks, Mermaid only when it materially improves understanding.
- Exact implemented names for classes, methods, annotations, tables, columns, files.
- Existing behaviour and recommendations clearly separated, everywhere.
- No repetition across sections; unfamiliar Spring/JPA behaviour explained in plain language.
- No speculative implementation details; no application-code changes; create or update only the
  requested Markdown file; leave unrelated working-tree changes untouched.
