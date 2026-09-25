---
name: writing-feature-docs
description: >
  Use when asked to document, write up, or explain a backend feature or module of this Spring Boot
  repo for other engineers or for Notion — "document the invitation feature", "write docs for the
  RBAC work", "generate feature documentation for this diff". Not for the frontend API contract
  (docs/api-contracts) or the per-module raw-query docs (docs/queries), which have their own formats.
---

# Writing Feature Docs

Produce a **short, plain-language** Markdown document for **one feature** of this repo, written for
an engineer who is new to the codebase — and possibly to Spring Boot and PostgreSQL. After a
five-minute read they should know what the feature does, how to call it, and where the deep details
live. This document is the map, not the territory: depth stays in the linked references
(`docs/queries/*`, `docs/api-contracts/*`, the code). Section structure and per-section budgets:
**[template.md](template.md)** — follow it top to bottom.

## The shape (what "short" means)

- **100–150 lines total; 200 is the hard ceiling** even for the largest feature.
- Short sentences, plain words. Define each technical term in half a sentence at first use.
- A small table or three bullets over a paragraph; one code/DDL block only where it earns its place.
- SQL lives in `docs/queries/<module>.md` and payload contracts in `docs/api-contracts/` — **link
  them, restate nothing**. One link beats ten restated lines.
- At most one Mermaid diagram, and only for a genuinely non-obvious flow.

## Scope

The scope arrives as the last line of this skill's text, `ARGUMENTS: <text>` (what the user typed
after `/writing-feature-docs`): a module (`label`), a feature spanning modules (`project membership
RBAC`), a ticket link, or `current diff`. Line missing or empty → ask which feature before reading
anything. Determine the **actual** scope from the implementation, not from the request wording.

## Sources of truth (read before writing)

| Question | Where the answer is |
|---|---|
| Conventions, wire format, auth model, gotchas | `CLAUDE.md` |
| Nest parity, route table, deliberate compatibility differences | `MIGRATION.md` |
| Exact SQL every endpoint runs, per module | `docs/queries/<module>.md` — link it, don't re-derive |
| Socket.IO messages, payloads, rooms | `docs/api-contracts/socket-events.md` |
| Schema, constraints, indexes, enums | `src/main/resources/db/migration/V*.sql` — never the JPA annotations alone |
| Behaviour and edge cases | the controllers, services, repositories, listeners **and their tests** |

Java is the active implementation. Comments and `MIGRATION.md` mention the NestJS origin —
describe it only as history, and use Java examples only.

## Workflow

1. Inspect the scope: `git diff` when the scope is "current diff", then source, tests, migrations,
   and the existing `docs/queries` / `docs/api-contracts` files.
2. Fill the template with **exact** class, method, table, column and file names — but only the ones
   the reader needs; everything else is a link.
3. Label every recommendation `Recommended improvement`; everything else is implemented behaviour.
4. Run the quality gate below, then write the file.

## Output

- Path: `docs/features/<feature>.md` (kebab-case), or the path the user names.
- Notion-importable Markdown (headings, tables, fenced blocks).
- Create or update only that file. Never touch application code or unrelated working-tree changes.
- This skill documents; it does not fix. Found a bug or a stale doc? Record it under
  "Gotchas & Limitations" and tell the user.
- Existing long-form feature docs are not retroactively rewritten unless asked.

## Quality gate

- A newcomer can read the document end-to-end in about five minutes and explain the feature back.
- Within the line budget (≤ 150 target, 200 ceiling).
- Every endpoint, class, table, constraint and index named exists in the code or the migrations.
- No SQL or payload shapes restated from `docs/queries/*` / `docs/api-contracts/*` — linked instead.
- Implemented vs `Recommended improvement` clearly separated; jargon defined at first use.

## Common mistakes

| Mistake | Fix |
|---|---|
| Writing for a senior audience | The reader is new here — plain words, define terms |
| Restating linked docs (query SQL, contract payloads) | Link them; keep only what the reader needs in place |
| Padding a section to look complete | One honest line beats a paragraph of generalities |
| Documenting the request instead of the code | Read the implementation first; scope comes from it |
| Recommendations written as facts | Move them under `Recommended improvement` |
| Fixing code or other docs while documenting | Note it, report it, leave it |
