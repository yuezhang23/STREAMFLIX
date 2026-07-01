-- Indexes to keep catalog reads flat as the catalog grows from ~66 to thousands of rows
-- (the OLTP streaming load simulator seeds a 5k+ YouTube-sourced catalog).

-- Recommendation / browse queries filter and sort by recency.
CREATE INDEX IF NOT EXISTS idx_videos_release_year ON videos (release_year);

-- The search endpoint matches on title (ILIKE). A trigram GIN index keeps substring
-- search fast under load instead of degrading to a sequential scan at scale.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS idx_videos_title_trgm ON videos USING gin (title gin_trgm_ops);

-- watch_events is the highest-volume OLTP write table under streaming load; a composite
-- index supports the common per-user recency read pattern without widening write cost much.
CREATE INDEX IF NOT EXISTS idx_watch_user_created ON watch_events (user_id, created_at DESC);
