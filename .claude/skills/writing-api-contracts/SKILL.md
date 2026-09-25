---
name: writing-api-contracts
description: >
  Use when asked to write or update a frontend API contract for this Spring Boot repo — "write the
  api contract for invitations", "document the HTTP contract for search", or when a change alters
  wire-visible behavior that a docs/api-contracts/*.md file records. Not for feature docs
  (docs/features — writing-feature-docs) or the per-module raw-SQL docs (docs/queries).
---

# Writing API Contracts

Produce the **frontend integration contract** for one HTTP feature: the document a frontend
developer builds against without reading Java. Unlike feature docs, payloads are spelled out **in
full** here — exact request params, response JSON, TypeScript types, and error bodies. This is the
one place they live; `docs/features/*` and `docs/queries/*` link to it. Section structure:
**[template.md](template.md)**. Exemplar: `docs/api-contracts/task-search.md`.

## Scope

`ARGUMENTS: <text>` (what the user typed after `/writing-api-contracts`) names an endpoint, a
feature, or `current diff`. Missing/empty → ask which endpoints before reading anything. One file
per feature: `docs/api-contracts/<feature>.md`, kebab-case.

**Socket.IO is different:** any WS-visible change updates the single `socket-events.md` **in
place** (it has its own structure — message catalogue + reference implementation); never create a
second WS file. `login-rate-limit.md` is a Vietnamese hands-on practice doc — a deliberate
exception, not the format to copy.

## Sources of truth (read before writing — never guess wire shapes)

| Fact | Where the truth is |
|---|---|
| Routes, params, status codes | the controller + `@Operation`/`@ApiResponse` annotations |
| Request validation rules & exact 400 messages | the DTO's `common/validation` annotations + `WebLayerTest` |
| Response fields & ordering | the entity `toJson()` / response record — never guess from the entity fields |
| Error bodies (401/403/404/409/429/500) | `GlobalExceptionHandler`, `JwtAuthInterceptor`, `WebLayerTest` pins |
| Date format | `JacksonConfig` — `2026-01-01T00:00:00.000Z` (with milliseconds) |
| Behavior semantics (ordering, scoping, defaults) | the service + its tests; `docs/queries/<module>.md` |

## Rules

- **Exactness over prose.** snake_case fields exactly as `toJson()` emits them; pagination meta
  uses `totalPages` (the one camelCase key); validation messages copied verbatim from the
  validation engine, not paraphrased.
- Full example payloads for success and every error the endpoint can return, plus a
  `### Typed payload` block of TypeScript interfaces.
- Written for the frontend: include integration rules (cache keys, debounce, page resets, header
  handling) — things the backend knows and the frontend would otherwise rediscover.
- **Changelog** entry (date + ticket id) on every create and every update.
- Cross-link both ways: this contract links `docs/queries/<module>.md`; that file's header links
  back here.
- English by default; another language only if the user asks.
- Documents only — never change code. A mismatch between code and an existing contract is a
  finding to report, not to silently paper over.

## Workflow

1. Read the controller, DTOs, `toJson()`s, `GlobalExceptionHandler` paths and the tests that pin
   the wire format (`WebLayerTest`).
2. Fill the template; copy real shapes, don't compose them from memory.
3. Run the quality gate; write the file; add/refresh the cross-link in `docs/queries/<module>.md`.

## Quality gate

- Every route, param, field, status code and error message exists in the code or a test pins it.
- Example JSON parses; TS types match the example field-for-field (names, nullability, arrays).
- Dates show the `.000Z` format; no camelCase request/response fields beyond the known exceptions.
- Changelog updated; cross-links present; no SQL restated (that's `docs/queries/*`).

## Common mistakes

| Mistake | Fix |
|---|---|
| Guessing validation messages | Copy from the validation engine / `WebLayerTest` assertions |
| Deriving response fields from the JPA entity | Read `toJson()` — hidden columns and relation gating live there |
| Timestamps without milliseconds | `JacksonConfig` emits `.000Z` — examples must too |
| New file for a WS change | Update `socket-events.md` in place |
| Restating SQL or feature design | Link `docs/queries/*` / `docs/features/*` |
| Updating the contract but not the changelog | Every edit gets a dated changelog line |
