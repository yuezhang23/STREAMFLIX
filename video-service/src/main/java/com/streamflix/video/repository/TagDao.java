package com.streamflix.video.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-side queries that back the per-user "recommendation tags" feature. Unlike the JPA
 * {@link VideoRepository} (which maps the {@code videos} aggregate), this joins the behavioral
 * tables ({@code watch_events}, {@code ratings}) to {@code videos.genre} to derive a per-user
 * genre-affinity signal and a global popularity ordering — all owned by video-service's schema,
 * so no cross-service call is needed.
 */
@Repository
public class TagDao {

    private final JdbcTemplate jdbc;

    public TagDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Per-genre affinity score for a single user, derived from their own behavior:
     * minutes watched (watch_events) plus a weighted rating signal (ratings). Only genres the user
     * has actually interacted with appear. Insertion order is score-descending.
     */
    public Map<String, Double> userGenreScores(long userId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        jdbc.query("""
                SELECT genre, SUM(score) AS score
                FROM (
                    SELECT v.genre AS genre, (w.watched_sec / 60.0) AS score
                    FROM watch_events w JOIN videos v ON v.id = w.video_id
                    WHERE w.user_id = ?
                    UNION ALL
                    SELECT v.genre AS genre, (r.rating * 2.0) AS score
                    FROM ratings r JOIN videos v ON v.id = r.video_id
                    WHERE r.user_id = ?
                ) t
                GROUP BY genre
                ORDER BY score DESC
                """,
                rs -> { scores.put(rs.getString("genre"), rs.getDouble("score")); },
                userId, userId);
        return scores;
    }

    /**
     * All catalog genres ordered by global popularity (total watch events across all users),
     * including genres with zero watches so the full browse set is always available as chips.
     */
    public java.util.List<String> genresByPopularity() {
        return jdbc.queryForList("""
                SELECT v.genre
                FROM videos v
                LEFT JOIN watch_events w ON w.video_id = v.id
                GROUP BY v.genre
                ORDER BY COUNT(w.id) DESC, v.genre ASC
                """, String.class);
    }
}
