-- video-service OLTP tables (source of truth for catalog + transactional behavior).
-- Derived/analytical state lives in other services, fed via Kafka — no cross-service FKs.

-- Videos are metadata only (no media files / object storage). Each row references a real YouTube
-- video by id; thumbnails/embeds are derived from that id. The catalog is loaded at startup from
-- a curated resource (CatalogSeeder), so no seed rows are inserted here.
CREATE TABLE videos (
    id            BIGSERIAL PRIMARY KEY,
    youtube_id    VARCHAR(32),
    title         VARCHAR(255) NOT NULL,
    description   TEXT,
    genre         VARCHAR(64)  NOT NULL,
    channel       VARCHAR(255),
    duration_sec  INT          NOT NULL,
    release_year  INT          NOT NULL,
    thumbnail_url VARCHAR(512),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_videos_genre ON videos (genre);

CREATE TABLE watch_events (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    video_id    BIGINT      NOT NULL,
    watched_sec INT         NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_watch_user ON watch_events (user_id);
CREATE INDEX idx_watch_video ON watch_events (video_id);

CREATE TABLE ratings (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    video_id   BIGINT      NOT NULL,
    rating     INT         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, video_id)
);
