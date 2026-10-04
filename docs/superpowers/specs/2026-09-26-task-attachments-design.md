# Task attachments — add files and screenshots to a task (JSP-40)

Design for uploading, listing, downloading and removing files on a task. A Java-only
feature with no NestJS counterpart.

- **Ticket:** JSP-40
- **Branch:** `feat/JSP-40-task-attachments` off `develop`, PR against `develop`
- **Status:** design approved in chat; implementation not started

## 1. Goal

A task can carry files — mainly screenshots and PDFs for bug reports. People in the project
can see who attached what and download it; people outside the project cannot tell an
attachment exists.

### Product decisions (answered 2026-09-26)

| Question | Decision |
|---|---|
| Max file size | **10 MB** (config value `ATTACHMENT_MAX_SIZE`) |
| Allowed types | **PNG, JPEG, GIF, WebP, PDF** — checked from the file's bytes, not its name; **Markdown** (added 2026-10-04) — `.md`/`.markdown` name + UTF-8 text content, since it has no signature |
| Who can delete | **The uploader (while still a member) or a project admin/owner** |
| Per-project quota | **Not now.** `size_bytes` is stored, so a `SUM` check is a small follow-up |
| Storage | **S3 API on Cloudflare R2** — one private bucket, `task-attachments`, shared by dev and production because this is a learning project (changed 2026-10-04 from "Supabase Storage in production, MinIO locally": no Docker on the dev machine, no egress fees, and dev then tests the same provider production runs); MinIO kept as the offline alternative |
| Orphaned files | **A database deletion queue** drained by a scheduled job (§5) |

### Out of scope (separate tickets)

1. The frontend (`[Web]` ticket follows).
2. Thumbnails and inline previews.
3. Attachments on comments, and pasting images into a task description.
4. A per-project storage quota.
5. Activity-history entries and a Socket.IO broadcast for attachment changes.
6. A storage sweeper for the rare file a server crash can strand (§5, "Known gap").

## 2. API

All four routes carry `@JwtAuth` and live under the task, so the project-role gate runs
before the attachment is looked up (CLAUDE.md "gate before lookup").

| Route | Minimum role | Success |
|---|---|---|
| `POST /tasks/{taskId}/attachments` — `multipart/form-data`, one part named `file` | MEMBER | 201 + attachment |
| `GET /tasks/{taskId}/attachments?page=&limit=` | VIEWER | 200 `PaginatedResponse`, newest first |
| `GET /tasks/{taskId}/attachments/{attachmentId}/download` | VIEWER | 200 `{ url, expires_at }` |
| `DELETE /tasks/{taskId}/attachments/{attachmentId}` | MEMBER **and** (uploader **or** ADMIN+) | 204 |

- Path params use `@Param(pipe = Param.Pipe.UUID)`; `page` / `limit` follow `CommentQueryDto`
  (1-based, default 20, max 100). Sort: `created_at DESC, id DESC`.
- **One file per request.** "Attach one or more files" = one request per file, so each file
  gets its own error and there is no partial-success response.
- **Download returns JSON, not a redirect.** The frontend authenticates with a bearer header,
  which a plain link cannot carry. It calls this route, then navigates to `url`. The link is
  a signed URL valid for **5 minutes** (`ATTACHMENT_DOWNLOAD_URL_TTL`). A link issued before a
  delete keeps working until it expires or the file is removed (§5), whichever comes first.

### Attachment JSON (`TaskAttachment.toJson()`)

```json
{
  "id": "5b1e0c1a-…",
  "task_id": "9c2d7f3e-…",
  "file_name": "login-bug.png",
  "content_type": "image/png",
  "size_bytes": 482133,
  "uploaded_by": { "id": "…", "full_name": "…", "...": "User.toJson() fields" },
  "created_at": "2026-09-26T10:00:00.000Z"
}
```

- `storage_key` is never serialized.
- `uploaded_by` is always present: every query that serializes attachments loads the
  uploader (the `Comment.author` pattern). It is `null` once the uploader's account is
  deleted (`ON DELETE SET NULL`).

### Download JSON (`DownloadUrlDto`)

```json
{ "url": "https://<account-id>.r2.cloudflarestorage.com/task-attachments/tasks/…?X-Amz-…", "expires_at": "2026-09-26T10:05:00.000Z" }
```

### Delete rule, precisely

1. `ensureTaskRole(taskId, userId, MEMBER)` — non-member 404, viewer 403 (this also covers an
   uploader who was later demoted to viewer).
2. Load the attachment by `(attachmentId, taskId)` — missing → 404.
3. Allowed if `uploaded_by == userId`, or the caller's membership role ranks ≥ `ADMIN`
   (`projectAccessService.getMembership(projectId, userId)`). Otherwise 403
   `"Only the uploader or a project admin can delete this attachment"`.

## 3. Upload checks, in order

1. **JWT** — `JwtAuthInterceptor`. `spring.servlet.multipart.resolve-lazily: true` delays
   parsing the upload until after the interceptor, so an anonymous request gets 401 without
   the server reading the file.
2. **Role** — `ensureTaskRole(taskId, userId, MEMBER)`.
   *Accepted gap:* Spring resolves the `MultipartFile` argument (spooling it to a temp file)
   just before the service runs, so a logged-in non-member can still make the server read up
   to 10 MB. It is bounded and requires a valid login.
3. **Presence** — no `file` part → 400 `"File is required"`; 0 bytes → 400 `"File is empty"`.
4. **Size** — over the limit → 413 `"File is too large. The maximum size is 10 MB."`
   Raised by the service check *and* by a new `MaxUploadSizeExceededException` handler (which
   today falls through to a generic 500). The "10 MB" text comes from the configured value.
5. **Type, from the first bytes** (`AllowedFileType.detect(byte[])`, ~30 lines, no Tika):

   | Type | Signature | Stored `content_type` |
   |---|---|---|
   | PNG | `89 50 4E 47 0D 0A 1A 0A` | `image/png` |
   | JPEG | `FF D8 FF` | `image/jpeg` |
   | GIF | `GIF87a` / `GIF89a` | `image/gif` |
   | WebP | `RIFF` + 4 bytes + `WEBP` | `image/webp` |
   | PDF | `%PDF-` | `application/pdf` |
   | Markdown | none — name ends `.md`/`.markdown` (any case) **and** the whole file is UTF-8 text with no control characters except tab/CR/LF (BOM allowed) | `text/markdown; charset=utf-8` |

   No match → 415 `"File type is not allowed. Allowed types: PNG, JPEG, GIF, WebP, PDF, Markdown."`
   The browser's claimed `Content-Type` is ignored; the extension counts only for Markdown, and
   only together with the content check (a renamed binary fails it). Markdown downloads as an
   attachment like everything else, so embedded HTML never runs; a future inline preview must
   sanitize it. SVG is deliberately excluded (it can carry scripts).
6. **File name** (`FileNameSanitizer`) — keep only the part after the last `/` or `\`
   (strips `C:\fakepath\` and `../`), remove control characters, trim, cap at 255 characters
   while keeping the extension; blank → `attachment`. Then `withExtensionOf`: if the extension
   is not one of the detected type's (`png`; `jpg`/`jpeg`; `gif`; `webp`; `pdf`, any case),
   append the type's canonical one — PDF-headed bytes named `report.bat` download as
   `report.bat.pdf`, so a name can never disguise the content (changed after the final review).
   Used for display and the download name only — never in the storage key.

### Error bodies

Same Nest `createBody` shape as every other error. Two new classes in `common/exception`,
named after their NestJS equivalents:

```json
{ "message": "File is too large. The maximum size is 10 MB.", "error": "Payload Too Large", "statusCode": 413 }
{ "message": "File type is not allowed. Allowed types: PNG, JPEG, GIF, WebP, PDF, Markdown.", "error": "Unsupported Media Type", "statusCode": 415 }
```

| Situation | Response |
|---|---|
| Non-member / unknown task | 404 task-flavored body from `ProjectAccessService` |
| Attachment not on this task | 404 `Attachment with id "<id>" not found` |
| Not a multipart request at all (e.g. JSON) | Spring passes `file = null` → 400 `File is required` |
| Broken multipart body (`MultipartException`) | 400 `Request must be multipart/form-data with a file part named "file"` |
| Storage error (`SdkException`) | `log.error` + 503 `File storage is unavailable, please try again` (existing `ServiceUnavailableException`; no S3 text leaks) |
| Task deleted between role check and insert (FK violation) | delete the uploaded file, then 404 task-flavored body |

The new `GlobalExceptionHandler` entries cover multipart exceptions only, so no other
route's behavior changes.

**Oversized uploads and Tomcat.** When a request exceeds the limit, Tomcat stops reading;
if more than `server.tomcat.max-swallow-size` (default 2 MB) is still unsent it closes the
connection and the browser sees a network error instead of the 413. Raise
`max-swallow-size` (e.g. 50 MB) so moderately oversized files still get a readable 413; the
`[Web]` ticket should also check the size before uploading. Pinned by the `storage-it` test.

## 4. Schema — `V6__create_task_attachments.sql`

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

-- Files still waiting to be removed from storage.
CREATE TABLE IF NOT EXISTS storage_deletions (
  storage_key VARCHAR(512) PRIMARY KEY,
  queued_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  attempts    INT NOT NULL DEFAULT 0
);

CREATE OR REPLACE FUNCTION fn_queue_attachment_file_deletion() RETURNS trigger AS $$
BEGIN
  INSERT INTO storage_deletions (storage_key) VALUES (OLD.storage_key)
  ON CONFLICT DO NOTHING;
  RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_task_attachments_queue_file_deletion
  AFTER DELETE ON task_attachments
  FOR EACH ROW EXECUTE FUNCTION fn_queue_attachment_file_deletion();
```

- Row triggers also fire for rows removed by `ON DELETE CASCADE`, so deleting a task — or a
  parent task together with its subtasks (`tasks.parent_id ON DELETE CASCADE`) — queues all
  their files with no change to `TaskService`. The trigger style matches V1's
  `trg_tasks_set_ticket_id`.
- Projects cannot be deleted while they have columns (`ON DELETE RESTRICT`), so task deletion
  is the only bulk path today; any future path is covered by the trigger automatically.
- The entity uses `@UuidGenerator` like `Comment`; the storage key is generated separately
  (§5), so the row id is not needed before the upload.

## 5. Storage and file lifecycle

**Bucket:** one private R2 bucket, `task-attachments`, shared by dev and production. The app
never creates it (a person does, §8). A real product would give each environment its own bucket
and token, so dev uploads and the cleanup job never touch production files.

**Key:** `tasks/{taskId}/{random UUID}`. No filename (odd characters can't break paths) and
no project id (a task can move between projects via `PATCH /tasks/{id}/move`; its files
follow it).

**Object metadata set on upload:**
- `Content-Type` = the detected type from §3, not the client's claim.
- `Content-Disposition` = `attachment; filename="<ASCII fallback>"; filename*=UTF-8''<percent-encoded name>`,
  so browsers save the file under its original name instead of rendering it. Set at upload
  time (R2 supports it on `PutObject`) rather than as a presign override.

**Upload flow** (`AttachmentService.upload`, not one big transaction):
1. Checks from §3.
2. `fileStorage.put(key, …)` — no DB connection is held during the upload; the Supabase pool
   is only 3–5 connections (CLAUDE.md gotcha).
3. `repository.save(attachment)` in its own short transaction, then re-fetch with the
   uploader loaded (save-then-refetch).
4. If step 3 throws: `fileStorage.delete(key)` best-effort (log if that fails too), then
   rethrow — FK violation → 404 task body; anything else → `log.error` + generic 500.

**Delete flow:** delete the row. The trigger queues the file; nothing in the request calls
storage.

**Cleanup job** (`StorageDeletionJob`, `@Scheduled(fixedDelay = 60 s)`), per run:
1. `SELECT storage_key FROM storage_deletions ORDER BY attempts, queued_at LIMIT 100` —
   fresh keys first, so a few keys that keep failing can never block the queue.
2. If none, stop (no storage call). Otherwise one `DeleteObject` call per key; a key that no
   longer exists counts as deleted.
3. `DELETE` the succeeded rows; `attempts = attempts + 1` on the failed ones and `log.warn`
   each. They are retried next run.

No row lock and no transaction around the storage calls. That means no pooled DB
connection is held while talking to storage. Deleting a file is idempotent, so the worst
case with two app instances is the same file deleted twice. Single `DeleteObject` calls
instead of one `DeleteObjects` batch: the batch call requires a request checksum
(`Content-MD5`/CRC) that S3-compatible stores handle inconsistently (§7), and 100 calls a
minute is trivial.

**Known gap:** a server crash between step 2 and step 3 of the upload strands a file with no
row. Rare; documented, not handled (follow-up: a sweeper comparing bucket keys to rows).

## 6. Module shape

```
src/main/java/com/kanban/common/storage/
  FileStorage.java               interface: put(key, InputStreamSource, size, contentType, downloadName) — re-openable, because SDK retries re-read the body,
                                 delete(key), signedDownloadUrl(key, ttl) → URI
  StorageException.java          the one exception FileStorage throws (wraps SdkException),
                                 so callers never see AWS types
  S3FileStorage.java             AWS SDK v2 S3Client + S3Presigner implementation;
                                 Content-Disposition built with Spring's ContentDisposition
  StorageProperties.java         record bound to app.storage.*

src/main/java/com/kanban/config/
  StorageConfig.java             S3Client / S3Presigner beans, @EnableScheduling,
                                 MultipartConfigElement built from ATTACHMENT_MAX_SIZE

src/main/java/com/kanban/modules/attachment/
  TaskAttachment.java            @Entity, table task_attachments; toJson()
  AttachmentRepository.java      page by task (@EntityGraph uploader, explicit countQuery),
                                 findByIdAndTaskId, findOneById (with uploader)
  AttachmentService.java         gate → validate → put → save/compensate; list; download; delete
  AttachmentController.java      the four routes
  AllowedFileType.java           enum: signature → MIME + label
  FileNameSanitizer.java         §3 step 6
  AttachmentProperties.java      record bound to app.attachments.* (max-size, download-url-ttl)
  StorageDeletionJob.java        §5 cleanup job
  StorageDeletionQueries.java    interface — batch select (no lock, §5), delete, bump attempts
  JpaStorageDeletionQueries.java @Repository, EntityManager native SQL
  dto/AttachmentQueryDto.java    ValidatedDto — page, limit
  dto/DownloadUrlDto.java        record — url, expires_at
```

- **Validation exception:** the upload route takes
  `@RequestPart(value = "file", required = false) MultipartFile file`, because
  `@ValidatedBody` cannot read multipart. The route has no other fields and every check
  lives in the service. Recorded in CLAUDE.md so it is not copied for JSON routes. Swagger
  documents the multipart body via `@io.swagger.v3.oas.annotations.parameters.RequestBody`.
- `TaskService` is not touched.

### Dependency

`software.amazon.awssdk:s3` with the AWS SDK BOM in `<dependencyManagement>`, using the sync
`url-connection-client` and excluding `netty-nio-client` and `apache-client`, so the SDK does
not bring its own Netty next to netty-socketio's. Verify with `mvn dependency:tree`.

## 7. Configuration

```yaml
spring.servlet.multipart:
  resolve-lazily: true          # limits come from StorageConfig's MultipartConfigElement
server.tomcat.max-swallow-size: 50MB
app:
  storage:
    endpoint: ${STORAGE_ENDPOINT:http://localhost:9000}
    region: ${STORAGE_REGION:us-east-1}
    bucket: ${STORAGE_BUCKET:task-attachments}
    access-key-id: ${STORAGE_ACCESS_KEY_ID:}
    secret-access-key: ${STORAGE_SECRET_ACCESS_KEY:}
    force-path-style: ${STORAGE_FORCE_PATH_STYLE:true}
  attachments:
    max-size: ${ATTACHMENT_MAX_SIZE:10MB}
    download-url-ttl: ${ATTACHMENT_DOWNLOAD_URL_TTL:5m}
```

- Multipart limits: max file size = `max-size`; max request size = `max-size` + 1 MB (room
  for multipart headers). One env var, set in one place.
- The app **boots without storage configured** (building an S3 client opens no connection;
  blank keys fall back to anonymous credentials instead of failing validation), so existing
  dev setups and `mvn test` keep working; attachment routes return 503 until storage is
  reachable.
- **S3 client settings:** `endpointOverride`, `forcePathStyle(true)`, and
  `requestChecksumCalculation(WHEN_REQUIRED)` / `responseChecksumValidation(WHEN_REQUIRED)` —
  AWS SDK v2 ≥ 2.30 adds default checksums that some S3-compatible stores reject. Confirm
  against R2 (storage IT and the smoke test).

## 8. Environments

- **Cloudflare R2 (dev and production):** R2 → create the **private** bucket `task-attachments` →
  Manage R2 API Tokens → a token with "Object Read & Write" scoped to that bucket. Endpoint
  `https://<account-id>.r2.cloudflarestorage.com`, region `auto`. The keys stay in `.env` /
  server environment variables — never in the frontend, never in git.
- **Offline alternative:** `compose.storage.yml` — MinIO on `127.0.0.1:9000` (console `:9001`);
  create the bucket once with
  `docker compose -f compose.storage.yml exec minio mc mb --ignore-existing local/task-attachments`
  (no one-shot container: `up --wait` fails on containers that exit). `.env.example` shows the
  R2 settings, with MinIO as a comment.

## 9. Testing

**Unit (`mvn test`, no services):**
- `AttachmentServiceTest` — mocked repositories + `ProjectAccessService`, new
  `testing/FakeFileStorage` (in-memory). Cases:
  - roles: non-member 404 on every route; viewer 403 on upload/delete; viewer can list and
    get a download URL;
  - delete rule: uploader-member ok, other member 403, admin ok, owner ok, demoted uploader 403;
  - validation: missing file 400, empty 400, oversize 413, EXE renamed `.png` 415, PNG named
    `.pdf` accepted as `image/png` and named `report.pdf.png`, filename sanitized;
  - lifecycle: save throws → file deleted and error rethrown; FK violation → 404; storage
    `put` throws → 503 with no S3 text in the body.
- `AllowedFileTypeTest` (each signature, truncated input, near-misses), `FileNameSanitizerTest`,
  `StorageDeletionJobTest` (empty queue → no storage call; success → rows deleted; failure →
  `attempts` bumped).
- `WebLayerTest` — add `AttachmentController` + `@MockitoBean AttachmentService`; pin: 201
  body, 401, the 413 body when the mocked service throws `MaxUploadSizeExceededException`
  (proves the handler), 415, 400 missing file, 400 non-multipart, 400 bad UUID pipe, 204
  delete, download JSON shape.

**Real HTTP, default `mvn test` (no external services):**
- `AttachmentUploadHttpTest` — embedded Tomcat on a random port, mocked `AttachmentService`
  and auth, no PostgreSQL (the `LoginRateLimitHttpIT` setup without Redis): an upload over
  the limit gets a readable 413 body; an anonymous oversized upload gets 401, not 413
  (proves `resolve-lazily`). MockMvc cannot test either — it does no real multipart parsing
  and enforces no size limits.

**Integration (opt-in, new `-Pstorage-it` profile, against the R2 bucket via `-Dstorage.it.*` (unique `it/<uuid>/` prefix, so safe on the shared bucket), or MinIO via `compose.storage.yml`):**
- `S3FileStorageIT` — built through the real `StorageConfig` bean methods: put → signed URL →
  HTTP GET returns the same bytes and the `Content-Disposition` filename; `delete` of existing
  and missing keys.
- Split the Maven profiles so `redis-it` runs only the Redis ITs and `storage-it` only the
  storage ITs.

**Trigger (manual, local Postgres bench `kanban_bench`):** insert a parent task, a subtask
and an attachment on each; delete the parent; expect both keys in `storage_deletions`. The
script goes in `docs/queries/attachment.md` (the repo has no PostgreSQL integration tests).

**Manual smoke against the R2 bucket** before merging: upload, download via the
signed URL (original name kept, including a long non-ASCII one), delete, wait one job cycle,
confirm the object is gone.

## 10. Docs updated in the same change

- `docs/queries/attachment.md` — every query (derived finders, `@EntityGraph` joins, the
  trigger, the cleanup SQL) plus the trigger verification script.
- `docs/api-contracts/task-attachments.md` — via the `writing-api-contracts` skill, for the
  `[Web]` ticket.
- `CLAUDE.md` — module list (`modules/attachment`, `common/storage`), latest migration V6,
  env vars, storage compose + `-Pstorage-it` commands, the `@RequestPart` exception, the first
  `@Scheduled` job, gotchas (`resolve-lazily`, `max-swallow-size`, SDK checksum setting).
- `MIGRATION.md` — §1 runtime row, §2 module row, §5 V6, §6 item 14.
- `.env.example` — §7 variables.

## 11. Risks to verify early

1. **R2 + AWS SDK v2 checksums** (§7) — the first thing the R2 smoke test checks.
2. **Oversized upload returns a readable 413** under real Tomcat (§3) — `AttachmentUploadHttpTest`.
3. **`resolve-lazily` really defers parsing past `JwtAuthInterceptor`** —
   `AttachmentUploadHttpTest`: an anonymous oversized upload gets 401, not 413.
4. **Netty version clash** — `mvn dependency:tree` shows no `software.amazon.awssdk:netty-nio-client`.
5. **No container runtime on the dev machine** (checked 2026-09-26: no `docker`). `storage-it`
   now runs against the R2 bucket instead (2026-10-04); MinIO stays available for anyone with
   Docker. MinIO's community images stopped receiving updates in late 2025, so its tag is pinned.
