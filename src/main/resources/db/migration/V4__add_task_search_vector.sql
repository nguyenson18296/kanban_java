-- ---------------------------------------------------------------------------
-- tasks.search_vector — full-text search over title + description (JAV-34)
-- ---------------------------------------------------------------------------
-- Text-search configuration 'simple': task content is mixed Vietnamese/English/Finnish, so the
-- column does no stemming and drops no stop words — every word in every language is searchable
-- and matching is predictable whole-word matching. Every query must parse its input with the same
-- configuration (see modules/search/TaskSearchSql): websearch_to_tsquery('simple', :search).
-- Weights: title A (1.0) outranks description B (0.4) in ts_rank_cd.
-- Postgres keeps the column in sync on every INSERT/UPDATE (STORED generated column, PG 12+);
-- the JPA entity does not map it.
ALTER TABLE tasks ADD COLUMN search_vector tsvector GENERATED ALWAYS AS (
  setweight(to_tsvector('simple', title), 'A') ||
  setweight(to_tsvector('simple', coalesce(description, '')), 'B')
) STORED;

-- fastupdate = off: entries go straight into the index instead of a pending list, so the planner
-- costs the index correctly right after bulk writes (with the default pending list, a fresh 10k-row
-- load made every search fall back to a sequential scan until VACUUM ran). Task writes are
-- human-paced, so the extra per-row index work is negligible.
CREATE INDEX idx_tasks_search_vector ON tasks USING GIN (search_vector) WITH (fastupdate = off);
