-- Durable store of precomputed recommendation candidates (the offline candidate-generation output).
-- A scheduled job populates top-N candidates per user; the serving path reads these (Redis-warmed)
-- so feed generation is a fast lookup instead of an on-demand collaborative-filtering computation.

CREATE TABLE recommendation_candidates (
    user_id     BIGINT NOT NULL,
    video_id    BIGINT NOT NULL,
    score       DOUBLE PRECISION NOT NULL,
    rank        INT    NOT NULL,
    reason      VARCHAR(128),
    computed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_id)
);
CREATE INDEX idx_candidates_user_rank ON recommendation_candidates (user_id, rank);
