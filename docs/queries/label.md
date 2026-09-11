# Labels — raw PostgreSQL queries

Queries executed by `modules/label` (`LabelController` / `LabelService` / `LabelRepository`).
Labels are a **global** table — there is no `project_id` column — so there is no project-membership
gate here: since RBAC Task 12 (JAV-21) all five routes are `@JwtAuth` (any authenticated user; JWT
stops anonymous reads and mutation). Project-scoping labels is schema work, explicitly deferred.

**How to read this file**

- SQL is the *equivalent* of what Hibernate emits (column lists simplified so it pastes into `psql`).
- Parameters are `:name`. Example values: label id `5` (serial int).
- Table: `labels (id SERIAL PK, name VARCHAR(50) UNIQUE ("UQ_labels_name"), color VARCHAR(20),
  created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)`. The `task_labels (task_id → tasks, label_id →
  labels ON DELETE CASCADE, PK (task_id, label_id))` join table belongs to the task module
  ([task.md](task.md)); deleting a label cascades its `task_labels` rows.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | All labels | `LabelRepository.findAll` | `GET /labels` |
| 2 | Label by id | `LabelRepository.findById` | `GET /labels/{id}`, `PATCH /labels/{id}`, `DELETE /labels/{id}` (twice — see #5) |
| 3 | Insert label | `LabelRepository.saveAndFlush` (new) | `POST /labels` |
| 4 | Update label | `LabelRepository.saveAndFlush` (existing) | `PATCH /labels/{id}` |
| 5 | Delete label | `LabelRepository.deleteById` | `DELETE /labels/{id}` |
| 6 | Labels by ids | `LabelRepository.findByIdIn` | task module: `TaskService.resolveLabels` (`label_ids` on `POST /tasks`, `PATCH /tasks/{id}`, `POST`/`DELETE /tasks/{id}/labels`) |

## Queries

### 1. All labels

`LabelRepository.findAll()` — Spring Data `findAll`, no ordering clause. Serves `GET /labels` via
`LabelService.findAll` (response is a bare JSON array of `Label.toJson()`, not the `ApiListResponse`
envelope).

```sql
SELECT id, name, color, created_at, updated_at
FROM labels;
```

### 2. Label by id

`LabelRepository.findById(id)` — PK lookup behind `LabelService.findOneById`. Missing →
`404 Label with id "5" not found`. `update` and `remove` call it first as their existence check.

```sql
SELECT id, name, color, created_at, updated_at
FROM labels
WHERE id = :id;

-- :id = 5
```

### 3. Insert label

`LabelService.create` → `saveAndFlush(new label)`. `id` is DB-generated (serial,
`GenerationType.IDENTITY`). Unique-name violation → `409 Label with name "<name>" already exists`
(the existing body also echoes the driver message under `error` — a pre-existing leak, not to be copied).

```sql
INSERT INTO labels (name, color, created_at, updated_at)
VALUES (:name, :color, now(), now())
RETURNING id;
```

### 4. Update label

`LabelService.update` → `saveAndFlush(label)` on the row loaded by **#2**; only the fields present in
the PATCH body change in memory (`dto.has("name")` / `dto.has("color")`), but Hibernate writes every
mapped column. Unique-name violation → 409 as in #3.

```sql
UPDATE labels
SET name = :name, color = :color, updated_at = now()
WHERE id = :id;
```

### 5. Delete label

`LabelService.remove` → `findOneById` (**#2**) → `deleteById(id)`. Spring Data's `deleteById` loads
the entity itself before `em.remove`, so **#2** runs a second time inside it. `task_labels` rows
referencing the label are removed by the FK's `ON DELETE CASCADE`.

```sql
DELETE FROM labels WHERE id = :id;
```

### 6. Labels by ids (task module)

`LabelRepository.findByIdIn(ids)` — derived query. Not reachable from a label route:
`TaskService.resolveLabels` uses it to validate the `label_ids` a task request carries (an unknown id
→ 404 there). Listed because it is the only other reader of this table.

```sql
SELECT id, name, color, created_at, updated_at
FROM labels
WHERE id IN (:ids);

-- :ids = (5, 6)
```

## Endpoint query sequences

Every 🔒 route first reloads the caller (`user#2`, see [user.md](user.md)) — not repeated below.

| Endpoint | Queries, in order |
|---|---|
| `POST /labels` 🔒 | **#3** |
| `GET /labels` 🔒 | **#1** |
| `GET /labels/{id}` 🔒 | **#2** |
| `PATCH /labels/{id}` 🔒 | **#2** → **#4** |
| `DELETE /labels/{id}` 🔒 | **#2** (service) → **#2** (inside `deleteById`) → **#5** |

🔒 = `@JwtAuth`. No `ProjectAccessService` gate: labels are not project-scoped.
