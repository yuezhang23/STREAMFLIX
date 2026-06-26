-- OLAP star schema (separate analytics database). Read/aggregate-optimized; populated by the
-- batch ETL from the OLTP raw event store. Recompute-and-replace each run keeps the demo simple.

-- Date dimension
CREATE TABLE dim_date (
    date_key   INT  PRIMARY KEY,   -- yyyymmdd
    full_date  DATE NOT NULL
);

-- Daily fact of views/watch per video (the grain of the star)
CREATE TABLE fact_views (
    date_key   INT    NOT NULL,
    video_id   BIGINT NOT NULL,
    views      BIGINT NOT NULL,
    watch_sec  BIGINT NOT NULL,
    PRIMARY KEY (date_key, video_id)
);

-- Pre-aggregated per-video lifetime stats
CREATE TABLE agg_video_stats (
    video_id        BIGINT PRIMARY KEY,
    total_views     BIGINT NOT NULL,
    unique_viewers  BIGINT NOT NULL,
    total_watch_sec BIGINT NOT NULL,
    avg_rating      NUMERIC(3,2),
    ratings_count   BIGINT NOT NULL
);

-- Trending: recency-weighted recent engagement
CREATE TABLE agg_trending (
    video_id    BIGINT PRIMARY KEY,
    score       DOUBLE PRECISION NOT NULL,
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
