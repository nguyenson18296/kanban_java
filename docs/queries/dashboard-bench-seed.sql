-- Representative dataset for measuring the project dashboard query (JSP-44). See docs/queries/dashboard.md.
-- LOCAL ONLY: run it against the throwaway kanban_bench database on compose.postgres.yml, never Supabase:
--   docker compose -f compose.postgres.yml exec -T postgres psql -U kanban_it -d kanban_it -c 'CREATE DATABASE kanban_bench'
--   (start the app once against kanban_bench so Flyway creates the schema, then)
--   docker compose -f compose.postgres.yml exec -T postgres psql -U kanban_it -d kanban_bench < docs/queries/dashboard-bench-seed.sql
--
-- Shape: 30 users; 10 projects x 20 columns (the last 2 archived); per project 16,000 tasks + 4,000
-- subtasks (200,000 rows); 0-3 assignees per task (~300,000 task_assignees rows); due dates spread
-- over -60..+59 days (a quarter without one); the five statuses evenly. Deterministic (no random()),
-- so every run produces the same numbers.
BEGIN;

INSERT INTO users (email, full_name, password_hash)
SELECT 'bench' || i || '@example.com', 'Bench User ' || i, 'x'
FROM generate_series(1, 30) AS i;

INSERT INTO projects (id, name, tag, created_by)
SELECT 'BENCH' || lpad(i::text, 3, '0'), 'Bench project ' || i, 'BN' || i,
       (SELECT id FROM users WHERE email = 'bench1@example.com')
FROM generate_series(1, 10) AS i;

INSERT INTO project_members (project_id, user_id, role)
SELECT p.id, u.id, CASE WHEN u.email = 'bench1@example.com' THEN 'owner' ELSE 'member' END::project_role
FROM projects p CROSS JOIN users u
WHERE p.id LIKE 'BENCH%' AND u.email LIKE 'bench%@example.com';

INSERT INTO kanban_columns (name, project_id, position, is_archived)
SELECT p.id || '-col-' || c, p.id, c, c > 18
FROM projects p CROSS JOIN generate_series(1, 20) AS c
WHERE p.id LIKE 'BENCH%';

-- The ticket-id trigger bumps projects.ticket_counter once per row: 200,000 updates of 10 rows in one
-- transaction is very slow. Bypass it and number the tickets here instead.
ALTER TABLE tasks DISABLE TRIGGER trg_tasks_set_ticket_id;

INSERT INTO tasks (title, column_id, status, due_date, ticket_id, ticket_number)
SELECT 'Bench task ' || g, c.id,
       (ARRAY['open', 'in_progress', 'in_review', 'done', 'cancelled'])[1 + (g * 7) % 5]::tasks_status_enum,
       CASE WHEN g % 4 = 0 THEN NULL ELSE now() + ((g % 120) - 60) * interval '1 day' END,
       p.tag || '-' || g, g
FROM projects p
CROSS JOIN generate_series(1, 16000) AS g
JOIN kanban_columns c ON c.project_id = p.id AND c.position = 1 + g % 20
WHERE p.id LIKE 'BENCH%';

INSERT INTO tasks (title, column_id, status, due_date, parent_id, ticket_id, ticket_number)
SELECT 'Bench subtask ' || g, c.id,
       (ARRAY['open', 'in_progress', 'in_review', 'done', 'cancelled'])[1 + (g * 3) % 5]::tasks_status_enum,
       CASE WHEN g % 3 = 0 THEN NULL ELSE now() + ((g % 90) - 45) * interval '1 day' END,
       parent.id, p.tag || '-' || (16000 + g), 16000 + g
FROM projects p
CROSS JOIN generate_series(1, 4000) AS g
JOIN kanban_columns c ON c.project_id = p.id AND c.position = 1 + (g * 3) % 20
JOIN tasks parent ON parent.ticket_id = p.tag || '-' || (g * 4)
WHERE p.id LIKE 'BENCH%';

UPDATE projects SET ticket_counter = 20000 WHERE id LIKE 'BENCH%';
ALTER TABLE tasks ENABLE TRIGGER trg_tasks_set_ticket_id;

-- 0-3 assignees per task (ticket_number % 4), three different users when there are three.
INSERT INTO task_assignees (task_id, user_id)
SELECT t.id, u.id
FROM tasks t
JOIN kanban_columns c ON c.id = t.column_id
CROSS JOIN LATERAL generate_series(1, t.ticket_number % 4) AS k
JOIN users u ON u.email = 'bench' || (1 + (t.ticket_number + k * 7) % 30) || '@example.com'
WHERE c.project_id LIKE 'BENCH%';

COMMIT;

ANALYZE users, projects, project_members, kanban_columns, tasks, task_assignees;
