# Task subscriptions — raw PostgreSQL queries

Queries executed by `modules/subscription` for `/tasks/{taskId}/subscription`,
`/tasks/{taskId}/subscription/me` and `/tasks/{taskId}/subscribers`, plus the
auto-subscribe writes other services call. As of JAV-20 `SubscriptionController` gates all
four routes with `ProjectAccessService.ensureTaskRole(taskId, userId, viewer)`
([project.md](project.md) queries 9 + 1), replacing the previous
`SubscriptionService.ensureTaskExists` probe (that method remains, unused by the
controller). The internal auto-subscribe entry points (`subscribe`, `subscribeMany`,
called from `TaskService`/`CommentService`) are intentionally ungated — the acting user was
authorized by the calling service, and recipients (new assignees, @mentions) may differ
from the actor.

## Index

| # | Query | Java | Triggered by |
|---|---|---|---|
| 1 | Insert subscription (idempotent) | `TaskSubscriptionRepository.insertIgnore` | `POST /tasks/{taskId}/subscription`; auto-subscribe on create/assign/comment/@mention |
| 2 | Delete subscription | `TaskSubscriptionRepository.deleteByTaskIdAndUserId` | `DELETE /tasks/{taskId}/subscription` |
| 3 | My subscription row | `TaskSubscriptionRepository.findByTaskIdAndUserId` | `GET /tasks/{taskId}/subscription/me`; `POST` response |
| 4 | Subscriber ids of a task | `TaskSubscriptionRepository.findUserIdsByTaskId` | comment/status-change fan-out recipient lists |
| 5 | Subscribers + user rows | `TaskSubscriptionRepository.findByTaskIdWithUserOrderByCreatedAtAsc` | `GET /tasks/{taskId}/subscribers` |

## Queries

### 1. Insert subscription (native, verbatim)

```sql
INSERT INTO task_subscriptions (task_id, user_id, source)
VALUES (CAST(:taskId AS uuid), CAST(:userId AS uuid), CAST(:source AS task_subscription_source))
ON CONFLICT DO NOTHING;

-- :source = 'manual' | 'created' | 'assigned' | 'commented' | 'mentioned'
```

### 2. Delete subscription

```sql
DELETE FROM task_subscriptions WHERE task_id = :taskId AND user_id = :userId;
```

### 3. My subscription row

```sql
SELECT s.* FROM task_subscriptions s WHERE s.task_id = :taskId AND s.user_id = :userId;
```

### 4. Subscriber ids of a task

```sql
SELECT s.user_id FROM task_subscriptions s WHERE s.task_id = :taskId;
```

### 5. Subscribers + user rows

```sql
SELECT s.*, u.*
FROM task_subscriptions s
LEFT JOIN users u ON u.id = s.user_id
WHERE s.task_id = :taskId
ORDER BY s.created_at ASC;
```
