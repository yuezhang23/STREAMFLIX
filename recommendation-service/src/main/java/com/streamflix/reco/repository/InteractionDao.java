package com.streamflix.reco.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Data access for the user-item interaction matrix (JdbcTemplate for efficient upserts). */
@Repository
public class InteractionDao {

    public record Interaction(long userId, long videoId, double score) {
    }

    private final JdbcTemplate jdbc;

    public InteractionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Accumulate weighted signal for a (user, video) pair. */
    public void addScore(long userId, long videoId, double weight) {
        jdbc.update("""
                INSERT INTO user_item_interactions (user_id, video_id, score, updated_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (user_id, video_id)
                DO UPDATE SET score = user_item_interactions.score + EXCLUDED.score, updated_at = now()
                """, userId, videoId, weight);
    }

    public List<Interaction> findAll() {
        return jdbc.query(
                "SELECT user_id, video_id, score FROM user_item_interactions",
                (rs, i) -> new Interaction(rs.getLong("user_id"), rs.getLong("video_id"), rs.getDouble("score")));
    }
}
