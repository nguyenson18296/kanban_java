---
name: writing-feature-docs
description: >
  Use when asked to document, write up, or explain a backend feature or module of this Spring Boot
  repo for other engineers or for Notion — "document the invitation feature", "write docs for the
  RBAC work", "generate feature documentation for this diff". Not for the frontend API contract
  (docs/api-contracts) or the per-module raw-query docs (docs/queries), which have their own formats.
---

# Writing Feature Docs

Produce the single-source-of-truth Markdown document for **one feature** of this repo, written the
way a senior Java backend engineer writes for teammates who must understand, operate, maintain and
extend it — the *why* (design decisions, trade-offs) as much as the *what*. Section structure and
per-section contents: **[template.md](template.md)**. Follow it top to bottom; when the feature
has nothing for a section, keep the heading and say so in one line.

## Scope

The scope arrives as the last line of this skill's text, `ARGUMENTS: <text>` (what the user typed
after `/writing-feature-docs`): a module (`label`), a feature spanning modules (`project membership
RBAC`), or `current diff`. Line missing or empty → ask which feature before reading anything.
Determine the **actual** scope from the implementation, not from the request wording.

Depth follows the feature: a one-table module yields a short document. Keep every heading of the
template, but never pad a section — one line saying what does not apply beats a paragraph of
generalities.

## Sources of truth (read before writing)

| Question | Where the answer is |
|---|---|
| Conventions, wire format, auth model, gotchas | `CLAUDE.md` |
| Nest parity, route table, deliberate compatibility differences | `MIGRATION.md` |
| Exact SQL every endpoint runs, per module | `docs/queries/<module>.md` — reuse it, don't re-derive |
| Socket.IO messages, payloads, rooms | `docs/api-contracts/socket-events.md` |
| Schema, constraints, indexes, enums | `src/main/resources/db/migration/V*.sql` — never the JPA annotations alone |
| Behaviour and edge cases | the controllers, services, repositories, listeners **and their tests** |

Java is the active implementation. Comments and `MIGRATION.md` mention the NestJS origin —
describe it only as history, and use Java examples only.

## Workflow

1. Inspect the scope: `git diff` when the scope is "current diff", then source, tests, migrations, config.
2. Fill the template with **exact** class, method, annotation, table, column and file names.
3. Label every recommendation `Recommended improvement`; everything else is implemented behaviour.
4. Run the quality gate below, then write the file.

## Output

- Path: `docs/features/<feature>.md` (kebab-case), or the path the user names.
- Notion-importable Markdown: `#`/`##`/`###` headings, tables, fenced `java` / `sql` / `yaml` /
  `bash` blocks, Mermaid only where it materially helps.
- Create or update only that file. Never touch application code or unrelated working-tree changes.
- This skill documents; it does not fix. Found a bug or a stale doc? Record it under
  "Known limitations" and tell the user.

## Quality gate

- Every endpoint, table, event, constraint and index named exists in the code or the migrations.
- Each repository query appears **once**, in section 12, as method → JPQL (or the Spring Data CRUD
  operation) → approximate PostgreSQL, labelled approximate; other sections link to it.
- Security, error-handling and testing sections separate *implemented* from *recommended*.
- No section restates another; no NestJS/TypeScript presented as the active implementation; no secrets.

## Common mistakes

| Mistake | Fix |
|---|---|
| Documenting the request instead of the code | Read the implementation first; scope comes from it |
| Inferring indexes or constraints from JPA annotations | Quote the Flyway migration |
| "Hibernate emits exactly this SQL" | Say approximate; runtime SQL differs in aliases and column lists |
| Recommendations written as facts | Move them under `Recommended improvement` |
| Re-deriving SQL that `docs/queries/<module>.md` already has | Link and reuse |
| Fixing code or other docs while documenting | Note it, report it, leave it |
