-- OLTP raw event store owned by analytics-service. Every behavior event consumed from Kafka
-- is appended here (event_id UNIQUE => idempotent under at-least-once delivery). The batch ETL
-- reads this table and loads the OLAP star schema in the separate analytics database.

CREATE TABLE analytics_raw_events (
    id         BIGSERIAL PRIMARY KEY,
    event_id   VARCHAR(64) NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL,
    video_id   BIGINT      NOT NULL,
    type       VARCHAR(16) NOT NULL,
    value      DOUBLE PRECISION NOT NULL,
    ts         TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_raw_events_ts ON analytics_raw_events (ts);
CREATE INDEX idx_raw_events_video ON analytics_raw_events (video_id);
