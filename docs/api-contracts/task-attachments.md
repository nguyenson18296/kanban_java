# Task attachments contract — frontend integration

HTTP contract for attaching files and screenshots to a task (JSP-40), matching the current backend
implementation. Use this alongside Swagger (`/api/docs`); database queries are documented in
[attachment.md](../queries/attachment.md). Attachment changes emit no Socket.IO events.

| | |
|---|---|
| Endpoints | `POST` / `GET /api/tasks/{taskId}/attachments`, `GET /api/tasks/{taskId}/attachments/{attachmentId}/download`, `DELETE /api/tasks/{taskId}/attachments/{attachmentId}` |
| Base URL | Default `http://localhost:1996/api` |
| Auth | `Authorization: Bearer <access_token>` on all four |
| Scope | The task's project: viewers list and download, members upload, uploader or admin/owner deletes |
| Files | PNG, JPEG, GIF, WebP, PDF, Markdown (`.md`) — at most **10 MB** (server setting `ATTACHMENT_MAX_SIZE`), one file per request |
| Responses | Attachment object; list `{ data, meta: { page, limit, total, totalPages } }`; download `{ url, expires_at }` |
| Casing | Fields are **snake_case**; pagination uses **`totalPages`** |

**Changelog**

- 2026-10-04 (JSP-40): Markdown (`.md`/`.markdown`, UTF-8 text) is accepted as `text/markdown; charset=utf-8`.
- 2026-09-26 (JSP-40): download names get the detected type's extension when the sent one
  does not match; long non-ASCII names no longer hit a false `413`; corrected the error-check order.
- 2026-09-26 (JSP-40): documented the four attachment endpoints, file rules, download flow,
  errors, and frontend integration rules.

---

## 1. Request & authentication

Missing, invalid or expired tokens, and deleted or inactive users, get `401` on every route —
before anything else is checked, and before an upload body is read.

| Route | Minimum project role | Input |
|---|---|---|
| `POST /tasks/{taskId}/attachments` | member | `multipart/form-data` with one part named **`file`** |
| `GET /tasks/{taskId}/attachments` | viewer | query `page` (integer, default `1`, min `1`), `limit` (integer, default `20`, min `1`, max `100`) |
| `GET /tasks/{taskId}/attachments/{attachmentId}/download` | viewer | — |
| `DELETE /tasks/{taskId}/attachments/{attachmentId}` | member, and uploader or admin/owner | — |

`taskId` and `attachmentId` must be UUIDs. Unknown query parameters on the list route are rejected
with `400`. The upload route reads only the `file` part; other parts are ignored.

```bash
curl -X POST 'http://localhost:1996/api/tasks/9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f/attachments' \
  -H 'Authorization: Bearer <access_token>' \
  -F 'file=@login-bug.png'
```

```http
POST /api/tasks/9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f/attachments HTTP/1.1
Host: localhost:1996
Authorization: Bearer <access_token>
Content-Type: multipart/form-data; boundary=----kanban

------kanban
Content-Disposition: form-data; name="file"; filename="login-bug.png"
Content-Type: image/png

<file bytes>
------kanban--
```

## 2. Behavior

**File rules (upload).**

| Rule | What the server does |
|---|---|
| Type | Read from the file's first bytes; the browser's `Content-Type` is **ignored**: a PNG named `report.pdf` is stored as `image/png`; an `.exe` renamed `.png` is refused (`415`). **Markdown** has no signature, so a `.md`/`.markdown` file is accepted only if its whole content is UTF-8 text (a ZIP renamed `.md` gets `415`). SVG is not allowed. |
| Size | Over the limit → `413`. The limit is a server setting (10 MB today); the message names it. |
| Empty | A 0-byte file → `400 File is empty`. |
| Name | Kept for display and download, cleaned first: folder parts dropped (`C:\fakepath\shot.png` → `shot.png`), control/invisible characters removed, cut to 255 characters keeping the extension. Nothing left → `attachment`. Non-ASCII names (e.g. Vietnamese) are kept. If the extension does not match the detected type, the type's extension is appended: `report.pdf` holding a PNG → `report.pdf.png`, `report.bat` holding a PDF → `report.bat.pdf`. Use the returned `file_name`, not the name you sent. |
| Count | One file per request. Send several files as several requests. |

**Who sees what.** People outside the project get the same `404` as for a task that does not
exist — they cannot tell a task or its attachments exist. An attachment id that belongs to a
different task is also a `404` on that task's routes.

**Download.** The download route does not return the file. It returns a signed storage URL, valid
for **5 minutes**, that anyone holding it can open with no `Authorization` header. The browser
saves the file under its original name (it is sent as a download, not displayed inline).

**Delete.** `204` removes the attachment from the list immediately. The stored file is removed in
the background within about a minute. A signed URL issued before the delete may keep working until
the file is removed or the URL expires, whichever comes first. Deleting a task also deletes its
attachments, and its subtasks' attachments.

**Ordering.** The list is newest first (`created_at` descending, `id` breaking ties).

## 3. Successful responses

`POST` → `201`, one attachment:

```json
{
  "id": "5b1e0c1a-7d2e-4f3a-9b8c-1d2e3f4a5b6c",
  "task_id": "9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f",
  "file_name": "login-bug.png",
  "content_type": "image/png",
  "size_bytes": 482133,
  "uploaded_by": {
    "id": "11111111-1111-4111-8111-111111111111",
    "email": "son@example.com",
    "full_name": "Son Nguyen",
    "role": "backend_developer",
    "avatar_url": "https://api.dicebear.com/9.x/initials/svg?seed=SN",
    "is_active": true,
    "created_at": "2026-09-01T08:00:00.000Z",
    "updated_at": "2026-09-01T08:00:00.000Z"
  },
  "created_at": "2026-09-26T10:00:00.000Z"
}
```

`GET /tasks/{taskId}/attachments` → `200`:

```json
{
  "data": [
    {
      "id": "5b1e0c1a-7d2e-4f3a-9b8c-1d2e3f4a5b6c",
      "task_id": "9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f",
      "file_name": "login-bug.png",
      "content_type": "image/png",
      "size_bytes": 482133,
      "uploaded_by": null,
      "created_at": "2026-09-26T10:00:00.000Z"
    }
  ],
  "meta": { "page": 1, "limit": 20, "total": 1, "totalPages": 1 }
}
```

`GET …/download` → `200`:

```json
{
  "url": "https://<account-id>.r2.cloudflarestorage.com/task-attachments/tasks/9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f/0b6f2c1e-…?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Expires=300&X-Amz-Signature=…",
  "expires_at": "2026-09-26T10:05:00.000Z"
}
```

`DELETE` → `204`, empty body.

### Typed payload

```ts
export type UserRole =
  | 'backend_developer' | 'frontend_developer' | 'fullstack_developer'
  | 'qa' | 'devops' | 'designer' | 'product_manager' | 'tech_lead';

export type AttachmentContentType =
  | 'image/png' | 'image/jpeg' | 'image/gif' | 'image/webp' | 'application/pdf'
  | 'text/markdown; charset=utf-8';

export interface AttachmentUploader {
  id: string;
  email: string;
  full_name: string;
  role: UserRole; // Job role, not the project membership role.
  avatar_url: string;
  is_active: boolean;
  created_at: string; // ISO 8601, e.g. 2026-09-26T10:00:00.000Z
  updated_at: string;
}

export interface TaskAttachment {
  id: string;
  task_id: string;
  file_name: string;
  content_type: AttachmentContentType; // Detected by the server from the bytes.
  size_bytes: number;
  uploaded_by: AttachmentUploader | null; // null once the uploader's account is deleted.
  created_at: string;
}

export interface PaginationMeta {
  page: number;
  limit: number;
  total: number;
  totalPages: number;
}

export interface TaskAttachmentPage {
  data: TaskAttachment[];
  meta: PaginationMeta;
}

export interface AttachmentDownload {
  url: string; // Open without an Authorization header.
  expires_at: string; // After this the URL is refused.
}
```

Field notes: `uploaded_by` is always present (object or `null`). There is no public file URL on the
attachment itself; always go through the download route. The storage location is never exposed.

## 4. Edge cases & empty states

- A task with no attachments returns `{ "data": [], "meta": { "page": 1, "limit": 20, "total": 0, "totalPages": 0 } }`.
- `totalPages = ceil(total / limit)`. A page past the end returns `data: []` with the real `total`.
- Pages are offset-based: an upload or delete between two page requests shifts items across pages.
- An uploader who is later demoted to viewer, or removed from the project, can no longer delete
  their own files; an admin or owner still can.
- Moving a task to another project keeps its attachments; access follows the task's current project.

## 5. Error semantics

| Status | Situation | Frontend handling |
|---|---|---|
| `400` | No `file` part (including a JSON body), 0-byte file, broken multipart body, invalid `page`/`limit`, malformed UUID | Show the message; fix the request. |
| `401` | Missing/invalid/expired token, deleted or inactive user | Normal refresh/login flow. |
| `403` | Viewer uploading or deleting; member deleting someone else's file | Hide the action (see §6); show the message if it happens. |
| `404` | Task does not exist **or** caller is not in its project; attachment not on this task | Treat as "not found"; do not reveal more. |
| `413` | File larger than the limit | Show the message (it names the limit). |
| `415` | File type not allowed | Show the message (it lists the allowed types). |
| `503` | File storage is unavailable (upload, or signing a download URL) | Retryable error. |
| `500` | Unexpected failure (e.g. the row could not be saved after upload) | Retryable error. |

Upload errors, in the order the server checks them: `401`; then, while the body is read, an over-limit body (`413`) or a broken multipart body (`400`) — before the role check, so these reach non-members too (the response is the same whether the task exists); then role (`404`/`403`); then missing/empty file (`400`), size (`413`), type (`415`):

```json
{ "message": "File is required", "error": "Bad Request", "statusCode": 400 }
```

```json
{ "message": "File is empty", "error": "Bad Request", "statusCode": 400 }
```

```json
{ "message": "Request must be multipart/form-data with a file part named \"file\"", "error": "Bad Request", "statusCode": 400 }
```

```json
{ "message": "File is too large. The maximum size is 10 MB.", "error": "Payload Too Large", "statusCode": 413 }
```

```json
{ "message": "File type is not allowed. Allowed types: PNG, JPEG, GIF, WebP, PDF, Markdown.", "error": "Unsupported Media Type", "statusCode": 415 }
```

```json
{ "message": "File storage is unavailable, please try again", "error": "Service Unavailable", "statusCode": 503 }
```

Role and existence errors (note: these two shapes carry no `error` key):

```json
{ "statusCode": 403, "message": "This action requires at least member role" }
```

```json
{ "statusCode": 403, "message": "Only the uploader or a project admin can delete this attachment" }
```

```json
{ "statusCode": 404, "message": "Task with id \"9c2d7f3e-1b2a-4c5d-8e9f-0a1b2c3d4e5f\" not found" }
```

```json
{ "statusCode": 404, "message": "Attachment with id \"5b1e0c1a-7d2e-4f3a-9b8c-1d2e3f4a5b6c\" not found" }
```

List validation (`limit=101`), UUID pipe, and authentication:

```json
{ "message": ["limit must not be greater than 100"], "error": "Bad Request", "statusCode": 400 }
```

Other list messages include `page must not be less than 1`, `page must be an integer number`, and
`property sort should not exist`.

```json
{ "message": "Validation failed (uuid is expected)", "error": "Bad Request", "statusCode": 400 }
```

```json
{ "message": "Unauthorized", "statusCode": 401 }
```

Unexpected failure:

```json
{ "message": "Failed to save attachment", "error": "Internal Server Error", "statusCode": 500 }
```

## 6. Frontend integration rules

- **Check before uploading.** Reject files over 10 MB and types outside the allowed list in the
  browser first. The server still checks, but a very large file may fail as a network error before
  the `413` arrives.
- **Build the request with `FormData`** (`form.append('file', file)`) and do **not** set
  `Content-Type` yourself — the browser adds the multipart boundary.
- **One request per file.** Several files can upload in parallel; show progress and errors per file.
- **Downloading:** call the download route when the user clicks, then navigate to `url`
  (`window.location.assign(url)` or a temporary `<a href download>`). Never add the
  `Authorization` header to the storage URL, and never store or reuse a URL past `expires_at` —
  request a new one each time.
- **Show the delete button** only when `uploaded_by?.id` is the current user and their project role
  is member or higher, or when their project role is admin/owner. The server enforces the rule anyway.
- **Hide upload** for viewers.
- **Cache keys:** `['task-attachments', taskId, page, limit]`. Invalidate the task's attachment
  queries after an upload or delete; after deleting the last item on a page, go back one page.
- Markdown files download like any other attachment. If you ever preview one inline, render it
  with HTML sanitized — the server accepts any UTF-8 text in a `.md` file.
- Display `size_bytes` in human units (KB/MB) and `content_type` as the icon hint (image vs PDF).

## 7. Backend references

- Controller: [AttachmentController.java](../../src/main/java/com/kanban/modules/attachment/AttachmentController.java)
- Service: [AttachmentService.java](../../src/main/java/com/kanban/modules/attachment/AttachmentService.java)
- DTOs: [AttachmentQueryDto.java](../../src/main/java/com/kanban/modules/attachment/dto/AttachmentQueryDto.java),
  [DownloadUrlDto.java](../../src/main/java/com/kanban/modules/attachment/dto/DownloadUrlDto.java)
- Entity / `toJson()`: [TaskAttachment.java](../../src/main/java/com/kanban/modules/attachment/TaskAttachment.java)
- File rules: [AllowedFileType.java](../../src/main/java/com/kanban/modules/attachment/AllowedFileType.java),
  [FileNameSanitizer.java](../../src/main/java/com/kanban/modules/attachment/FileNameSanitizer.java)
- Error handlers: [GlobalExceptionHandler.java](../../src/main/java/com/kanban/common/exception/GlobalExceptionHandler.java)
- Wire-format tests: [WebLayerTest.java](../../src/test/java/com/kanban/WebLayerTest.java),
  [AttachmentUploadHttpTest.java](../../src/test/java/com/kanban/modules/attachment/AttachmentUploadHttpTest.java),
  [AttachmentServiceTest.java](../../src/test/java/com/kanban/modules/attachment/AttachmentServiceTest.java)
- SQL: [attachment.md](../queries/attachment.md)
