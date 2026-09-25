# Feature documentation template

One `# <Feature name>` title, a 2–3 sentence plain-words intro (what it is + links to the deep
references), then the eight numbered headings below **exactly as written**
(`## 1. What It Does` … `## 8. Where to Look Next`), in this order. Target **100–150 lines** for
the whole document; 200 is the hard ceiling for the largest features. A section with nothing to
say keeps its heading and one line saying so.

Write for an engineer who is **new to the codebase, and possibly to Spring Boot and PostgreSQL**:
short sentences; define each technical term in half a sentence the first time it appears
("GIN index — a Postgres index type built for finding words"); prefer a small table or three
bullets over a paragraph. Use the repository as the only source of truth — never invent endpoints,
tables, indexes or behaviour.

## 1. What It Does

The problem it solves, what the feature provides (3–4 bullets), and what is deliberately out of
scope (with ticket ids). ≤ 10 lines.

## 2. How It Works

The main flow as numbered steps with real class names (`Controller → Service → queries`). Then the
key design decisions, one line each, each carrying its one-line *why*. At most one Mermaid diagram,
only when the flow is genuinely non-obvious.

## 3. API

Per endpoint: method + full route (**with the `/api` prefix**), auth, a parameter table, one
*trimmed* example response, one error table. Link `docs/api-contracts/<file>.md` for the full
payload contract instead of restating it.

## 4. Database

What the feature added or reads: tables/columns/indexes, naming the Flyway migration file. Show
DDL only for what the feature **added**, copied from the migration. For every query the feature
runs, link `docs/queries/<module>.md` — never restate SQL here.

## 5. Security

Who can call it, how results are scoped, validation guards that matter, what prevents data leaks.
Anything not implemented goes under `Recommended improvement`.

## 6. Testing

Test classes and what each pins (one line per class), the `mvn` command to run them, and what is
**not** covered.

## 7. Gotchas & Limitations

Bullets a maintainer must know: invariants that must change together, caps that are load-bearing,
known limitations with the deferred ticket id, which docs to update alongside a change.
Suggestions are marked `Recommended improvement` — never written as facts.

## 8. Where to Look Next

Links only: the source package/files, `docs/queries/<module>.md`, `docs/api-contracts/*`, the
migration file, related feature docs, the Linear ticket.

---

## Output requirements

- Plain language throughout; jargon defined at first use.
- Exact implemented names for classes, methods, tables, columns, files.
- Within the line budget; no section restates another section or a linked doc.
- Notion-ready Markdown: `#`/`##`/`###` headings, tables, fenced `java` / `sql` / `bash` blocks.
- No speculative details; create or update only the requested file.
