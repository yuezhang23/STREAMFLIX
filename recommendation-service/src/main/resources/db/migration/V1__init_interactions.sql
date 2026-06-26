-- recommendation-service owns the derived user-item interaction matrix, built entirely from the
-- Kafka behavior stream (no reads of other services' tables). score accumulates weighted signal.

CREATE TABLE user_item_interactions (
    user_id    BIGINT NOT NULL,
    video_id   BIGINT NOT NULL,
    score      DOUBLE PRECISION NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, video_id)
);
CREATE INDEX idx_interactions_video ON user_item_interactions (video_id);
