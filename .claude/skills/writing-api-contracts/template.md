# API contract template

Modeled on `docs/api-contracts/task-search.md`. Title `# <Feature> contract — frontend
integration`, then the blocks below in order. Numbered `##` sections; rename/merge headings 2 and 4
to fit the feature (e.g. no pagination → no pagination section), but keep 1, 3, 5, 6, 7 always.
Aim for what the frontend needs — typically 150–300 lines; don't pad a simple endpoint.

**Intro paragraph** — one short paragraph: what the contract covers, matching backend ticket id,
links to Swagger (`/api/docs`), the SQL doc (`docs/queries/<module>.md`) and, if events exist,
`socket-events.md`.

**Summary table** — endpoint(s), base URL (`http://localhost:1996/api`), auth header, scope,
response envelope, casing notes.

**Changelog** — bullet list, newest first: `- YYYY-MM-DD (TICKET): what changed`.

## 1. Request & authentication

Auth requirements and what earns a 401. A table of query/body fields: name, type, default, rules
(caps, whitelist behavior for unknown keys). One `curl` example **and** the equivalent raw HTTP
request.

## 2. Behavior

The semantics the frontend must know to use the endpoint correctly: matching/ordering rules,
defaults, scoping, what is and isn't included. Small tables of input → meaning where they help.

## 3. Successful response

One full example JSON (realistic values, `.000Z` timestamps, snake_case keys). Then
`### Typed payload`: TypeScript interfaces matching the example field-for-field, including enums
and nullability. End with field notes: always-present arrays, fields deliberately absent, how to
use ids.

## 4. Edge cases & empty states

Pagination semantics (`meta`, `totalPages = ceil(total / limit)`), empty-state examples, pages past
the end, consistency caveats under concurrent changes.

## 5. Error semantics

A table: status → situation → frontend handling. Then one example body per distinct shape —
validation 400 (`{ message: [...], error, statusCode }` with **verbatim** messages), 401, and any
feature-specific errors (403/404/409/429 + headers like `Retry-After`). State what deliberately
does *not* error (e.g. non-membership → empty 200).

## 6. Frontend integration rules

Bullets: cache-key ingredients, when to reset page, debounce/cancellation, order preservation,
which response fields to trust for navigation, logout/invalidations.

## 7. Backend references

Relative links to the controller, DTOs, service, queries class, entities and the test class that
pins the wire format.
