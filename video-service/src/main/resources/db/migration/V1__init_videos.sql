-- video-service OLTP tables (source of truth for catalog + transactional behavior).
-- Derived/analytical state lives in other services, fed via Kafka — no cross-service FKs.

CREATE TABLE videos (
    id            BIGSERIAL PRIMARY KEY,
    title         VARCHAR(255) NOT NULL,
    description   TEXT,
    genre         VARCHAR(64)  NOT NULL,
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

-- Seed the catalog: 50 videos spread across 8 genres, with usable thumbnails.
INSERT INTO videos (title, description, genre, duration_sec, release_year, thumbnail_url)
SELECT
    'StreamFlix Original #' || g,
    'An auto-generated demo title in the ' ||
        (ARRAY['Action','Comedy','Drama','SciFi','Horror','Documentary','Romance','Thriller'])[1 + (g % 8)]
        || ' genre.',
    (ARRAY['Action','Comedy','Drama','SciFi','Horror','Documentary','Romance','Thriller'])[1 + (g % 8)],
    1800 + ((g * 37) % 5400),
    2005 + (g % 20),
    'https://picsum.photos/seed/streamflix' || g || '/320/180'
FROM generate_series(1, 150) AS g;
