# Kanban backend — Spring Boot port

Java 21 / Spring Boot 3.5 port of the NestJS service in the repository root. It
serves the **same HTTP API** (`/api/...`, port 1996) against the **same PostgreSQL
schema**, so the existing frontend keeps working. See [MIGRATION.md](MIGRATION.md)
for the module-by-module mapping, the test mapping and the compatibility notes.

## Prerequisites

- JDK 21 (`brew install openjdk@21`)
- Maven 3.9+ (`brew install maven`)
- PostgreSQL 13+ (local, or the existing Supabase instance)

## Configure

```bash
cp .env.example .env      # then edit values
```

`.env` is read from the working directory (like dotenv in the Nest app); every
variable can also be passed as a real environment variable.

| Variable | Default | Purpose |
| --- | --- | --- |
| `POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` | — | Database connection |
| `POSTGRES_SSLMODE` | `prefer` | JDBC `sslmode` (`require` for Supabase-style TLS without cert verification, `disable` for plain local Postgres) |
| `DB_POOL_SIZE` | `10` | Hikari max connections. **Supabase session pooler (port 5432) caps clients at `pool_size` (15 by default) per DB user, shared by all apps — keep this ≤ 3–5 or use the transaction pooler (port 6543).** |
| `JWT_SECRET` | **required** | HS256 signing secret (same value as the Nest app → tokens stay valid across both) |
| `JWT_EXPIRES_IN` | `1h` | Access-token lifetime (`ms` grammar: `1h`, `15m`, `30d`, `3600`) |
| `REFRESH_TOKEN_EXPIRES_IN` | `30d` | Refresh-token lifetime (`<n>d`, anything else → 30 days, as in Nest) |
| `NODE_ENV` | `development` | `production` disables Swagger |
| `PORT` | `1996` | HTTP port |
| `SOCKET_IO_PORT` / `SOCKET_IO_ENABLED` | `1997` / `true` | Socket.IO server (separate port, see MIGRATION.md) |
| `FLYWAY_ENABLED` / `FLYWAY_BASELINE_ON_MIGRATE` | `true` / `false` | Schema migrations |

## Database migrations

Schema is managed by Flyway (`src/main/resources/db/migration`). `V1__baseline_schema.sql`
recreates the schema TypeORM `synchronize` produced from the Nest entities **plus** the
stored procedures/trigger from `../sql/kanban_tasks.sql` that the task endpoints call
(`fn_set_ticket_id`, `fn_move_task`, `fn_reorder_task`, `fn_reorder_subtask`).

- **Fresh database:** just start the app — Flyway creates everything.
- **Database already created by the Nest app:** set `FLYWAY_BASELINE_ON_MIGRATE=true`
  for the first start. Flyway records V1 as the baseline and applies nothing (the
  script is idempotent anyway, so running it is also safe).
- New schema changes go in `V2__*.sql`, `V3__*.sql`, … — never edit an applied file.

## Run

```bash
mvn spring-boot:run                 # dev
mvn -DskipTests package && java -jar target/kanban-backend-1.0.0.jar
```

- API: `http://localhost:1996/api` (`GET /api` → `Hello World!`)
- Swagger UI: `http://localhost:1996/api/docs` (OpenAPI JSON at `/api/docs-json`) — hidden when `NODE_ENV=production`
- Socket.IO: `http://localhost:1997` (clients: `io('http://localhost:1997', { auth: { token } })`) — full message contract for frontend integration: [`docs/api-contracts/socket-events.md`](docs/api-contracts/socket-events.md)

## Test

```bash
mvn test                                    # everything
mvn test -Dtest=TaskServiceTest             # one class
```

Tests are plain JUnit 5 + Mockito unit tests (no database needed); `WebLayerTest`
boots the MVC layer with MockMvc to pin the wire format (validation messages, guard
401 body, pipe 400 body, unknown-route 404).

## Project layout

```
src/main/java/com/kanban
├── KanbanApplication.java          # bootstrap (Nest main.ts + app.module.ts)
├── AppController / AppService      # GET /api → "Hello World!"
├── config/                         # /api prefix, CORS, resolvers, Jackson dates, async, OpenAPI, env
├── common/
│   ├── exception/                  # Nest HttpException family + global handler (identical bodies)
│   ├── validation/                 # class-validator/class-transformer-compatible ValidationPipe
│   ├── pipes/                      # ParseUUIDPipe / ParseIntPipe / ParseProjectIdPipe
│   ├── util/                       # parseMentions, sanitize-html, ms durations, JS dates, PG errors
│   ├── api/                        # ApiListResponse, PaginatedResponse
│   └── events/                     # EventBus (EventEmitter2)
└── modules/<feature>/              # one package per Nest module: entity, repository, service, controller, dto
```
