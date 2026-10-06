# Task Attachments

Files on a task, mainly screenshots and PDFs for bug reports. Members upload through the backend,
and people download from a short-lived signed link straight from object storage (Cloudflare R2).
Four routes under `/api/tasks/{taskId}/attachments`, built in
[JSP-40](https://linear.app/java-son-182/issue/JSP-40). The NestJS app never had this feature. Deep
details: [docs/queries/attachment.md](../queries/attachment.md) ·
[frontend contract](../api-contracts/task-attachments.md).

## 1. What It Does

Before this, a bug report on a task couldn't include its screenshot. Now:

- **Upload** one file per request: PNG, JPEG, GIF, WebP, PDF or Markdown, up to 10 MB.
- **List** a task's attachments, newest first, paginated.
- **Download** through a signed URL that works for 5 minutes. The file never passes through the app.
- **Delete** (uploader or project admin/owner). Stored files are removed in the background, and so
  are the files of deleted tasks and subtasks.

Out of scope (separate tickets in the design spec): the frontend, thumbnails/previews, attachments on
comments, a per-project quota, activity entries and Socket.IO events, a sweeper for stranded files (§7).

## 2. How It Works

Code: `com.kanban.modules.attachment`, plus `com.kanban.common.storage`. **Object storage** keeps
files under a text key, like a key-value store for files. The app reaches it through the **S3 API**,
Amazon's storage protocol, which R2 and MinIO also speak.

Upload (`AttachmentService.upload`):

1. `JwtAuthInterceptor` checks the token before Tomcat parses the body (`resolve-lazily: true`), so
   anonymous uploads are never read.
2. `ensureTaskRole(taskId, userId, MEMBER)`: non-members get the masked 404 (§5).
3. The file must be there, non-empty, and within `ATTACHMENT_MAX_SIZE`.
4. `FileNameSanitizer.sanitize` cleans the name. `AllowedFileType.detect` matches the first 12 bytes
   against each format's **signature** (the fixed bytes every file of that format starts with).
   Markdown has none, so `detectMarkdown` needs a `.md`/`.markdown` name and an all-UTF-8 body. No
   match → 415.
5. `withExtensionOf` adds the detected extension if the name lacks it (`report.bat` → `report.bat.pdf`).
6. `FileStorage.put` stores the bytes under `tasks/<taskId>/<random uuid>`.
7. `AttachmentRepository.save` inserts the row. If the insert fails, the file is deleted again. A
   foreign-key violation (task deleted mid-upload) becomes the masked 404.

**Download**: viewer gate → `findByIdAndTaskId` → `FileStorage.signedDownloadUrl` → `{ url,
expires_at }`. A **signed URL** is a storage link with a time-limited signature in its query string.

**Delete**: member gate → uploader or admin/owner check → `deleteById`. A database **trigger** (SQL
Postgres runs on a table event) copies the row's `storage_key` into the `storage_deletions` queue.
Every 60 s `StorageDeletionJob` deletes up to 100 queued files. Successes leave the queue, and each
failure adds 1 to `attempts` (lowest `attempts` is retried first).

Key decisions, and why:

- **Type comes from the bytes**: the client controls the name and `Content-Type`. SVG is excluded
  because it can carry scripts.
- **No transaction open while calling storage**: the pool has 3–5 connections, and holding one
  through a 10 MB upload would block other requests.
- **The trigger queues deletions**: it catches cascades too, and a storage outage never fails a delete.
- **`Content-Disposition: attachment` is stored on each object**: browsers save the file instead of
  showing it, so HTML inside a Markdown or PDF file never runs.
- **The `FileStorage` interface throws only `StorageException`**: callers never see AWS SDK types,
  and tests use `FakeFileStorage`.

Settings (`.env`): `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_BUCKET`, `STORAGE_ACCESS_KEY_ID`,
`STORAGE_SECRET_ACCESS_KEY` (R2 values in `.env.example`; with blank keys the app boots and storage
calls fail), `ATTACHMENT_MAX_SIZE` (`10MB`) and `ATTACHMENT_DOWNLOAD_URL_TTL` (`5m`, 1 s to 7 days).

## 3. API

All four routes use `@JwtAuth`. `taskId` and `attachmentId` must be UUIDs.

| Route | Role | Success | Errors |
|---|---|---|---|
| `POST /api/tasks/{taskId}/attachments` | member | 201 + attachment | 400, 401, 403, 404, 413, 415, 503 |
| `GET /api/tasks/{taskId}/attachments` | viewer | 200 + page | 400, 401, 404 |
| `GET /api/tasks/{taskId}/attachments/{attachmentId}/download` | viewer | 200 `{ url, expires_at }` | 401, 404, 503 |
| `DELETE /api/tasks/{taskId}/attachments/{attachmentId}` | member + uploader, or admin/owner | 204 | 401, 403, 404 |

Upload is `multipart/form-data` with one part named `file`. The list takes `page` (default 1) and
`limit` (default 20, max 100). An attachment, trimmed:

```json
{ "id": "5b1e…", "task_id": "9c2d…", "file_name": "login-bug.png", "content_type": "image/png",
  "size_bytes": 482133, "uploaded_by": { "id": "1111…", … }, "created_at": "2026-09-26T10:00:00.000Z" }
```

Full payloads, upload check order, error bodies, frontend rules:
[task-attachments contract](../api-contracts/task-attachments.md).

## 4. Database

`V6__create_task_attachments.sql` added:

```sql
CREATE TABLE IF NOT EXISTS task_attachments (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  task_id      UUID NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  uploaded_by  UUID REFERENCES users(id) ON DELETE SET NULL,
  file_name    VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  size_bytes   BIGINT NOT NULL CHECK (size_bytes > 0),
  storage_key  VARCHAR(512) NOT NULL UNIQUE,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_task_attachments_task_id_created_at
  ON task_attachments (task_id, created_at DESC);

CREATE TABLE IF NOT EXISTS storage_deletions (
  storage_key VARCHAR(512) PRIMARY KEY,
  queued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts    INT NOT NULL DEFAULT 0
);
```

Plus the trigger `trg_task_attachments_queue_file_deletion` (`AFTER DELETE`, per row) and its function
`fn_queue_attachment_file_deletion`. Row triggers fire on cascades too, so deleting a task also
queues its subtasks' files. Deleting a user keeps their files and sets `uploaded_by` to `NULL`. Every
query, plus a `psql` script that checks the trigger: [docs/queries/attachment.md](../queries/attachment.md).

## 5. Security

- Viewers read, members upload. To delete you must be the uploader and still a member, or an
  admin/owner.
- **Masked 404**: a non-member gets the same 404 as for a missing task. `findByIdAndTaskId` scopes each
  attachment to the task in the path, so an id from another task or project is also 404. Members
  below the required role get 403.
- The bucket is private, and `storage_key` is never serialized. Files are reachable only through
  signed URLs that expire.
- Names lose folder parts and control/invisible characters (`U+202E` could make `invoice‮fdp.exe`
  display as `invoiceexe.pdf`).
- Storage and insert failures return a generic 503/500. SDK details go to the log only.
- An over-limit (413) or broken multipart body (400) fails before the role check. It reveals
  nothing, because the response is the same whether or not the task exists.
- A signed URL works for anyone holding it until it expires. After a delete it keeps working until
  the job removes the file, usually within a minute.
- `Recommended improvement`: a bucket and keys per environment. Dev and production share
  `task-attachments` (a deliberate learning-project choice), so a leaked dev key exposes production.

## 6. Testing

- `AttachmentServiceTest` (28): role gate before file checks, every 400/413/415/503, type and extension
  rules, cleanup when the insert fails, the file kept once its row commits, delete permissions.
- `AllowedFileTypeTest` (6), `FileNameSanitizerTest` (9): signatures, look-alikes, the Markdown check,
  path stripping, `U+202E`, cutting names at 255 characters without splitting an emoji.
- `TaskAttachmentTest` (2), `StorageDeletionJobTest` (4), `S3FileStorageTest` (5): `toJson()` shape,
  queue draining and failure counting, signed-URL shape and booting without keys.
- `AttachmentUploadHttpTest` (4): uses a real embedded Tomcat, because MockMvc doesn't parse multipart.
  Pins a readable 413, 401 before parsing, and long Vietnamese names.
- `WebLayerTest`: 11 attachment tests (status codes and error bodies).
- `S3FileStorageIT` (4, opt-in): against real R2 or MinIO, with `mvn -Pstorage-it verify …` (see `CLAUDE.md`).

```bash
mvn test -Dtest='AttachmentServiceTest,AllowedFileTypeTest,FileNameSanitizerTest,TaskAttachmentTest,StorageDeletionJobTest,S3FileStorageTest,AttachmentUploadHttpTest'
```

Not covered: the SQL and the trigger (no PostgreSQL tests here; use the `psql` script), the 60 s
schedule, and several instances running the job at once.

## 7. Gotchas & Limitations

- **Stranded files**: a crash between the upload and the insert leaves a file with no row, which
  nothing deletes. This is a known gap; the sweeper is out of scope.
- Never hold a database transaction while calling `FileStorage`. Upload first, then insert.
- **Upload settings to keep**: `resolve-lazily: true` (JWT first), `max-swallow-size: 50MB` (readable
  413), `max-part-header-size: 2KB` (no false 413 on long non-ASCII names). Size limits come only from
  `ATTACHMENT_MAX_SIZE`, never `spring.servlet.multipart.max-*`.
- Don't add `consumes = multipart/…` to the upload mapping. A JSON request would get a generic 500
  instead of `400 File is required`.
- SDK checksums are set to `WHEN_REQUIRED`, and deletes use single `DeleteObject` calls, because
  S3-compatible stores handle checksums inconsistently.
- The app never creates the bucket. Every instance runs the job with no lock, so at worst a file is
  deleted twice (harmless).
- No Socket.IO events, so the frontend refetches. Offset pagination shifts items when files change.
- Changing behaviour? Update in the same PR: `docs/queries/attachment.md`,
  `docs/api-contracts/task-attachments.md`, `MIGRATION.md` (§6 item 14), and this file.

## 8. Where to Look Next

- Code: `src/main/java/com/kanban/modules/attachment/`, `src/main/java/com/kanban/common/storage/`,
  `src/main/java/com/kanban/config/StorageConfig.java`
- SQL: [docs/queries/attachment.md](../queries/attachment.md) · contract:
  [docs/api-contracts/task-attachments.md](../api-contracts/task-attachments.md)
- Migration: `src/main/resources/db/migration/V6__create_task_attachments.sql`
- Design spec: [2026-09-26-task-attachments-design.md](../superpowers/specs/2026-09-26-task-attachments-design.md)
- Related: [project-membership-rbac.md](project-membership-rbac.md) (the role gate)
- Ticket: [JSP-40](https://linear.app/java-son-182/issue/JSP-40) · branch `feat/JSP-40-task-attachments`
