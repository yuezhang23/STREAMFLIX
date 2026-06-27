package com.streamflix.reco.repository;

import com.streamflix.reco.dto.RecItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Durable store of precomputed recommendation candidates. */
@Repository
public class CandidateDao {

    private final JdbcTemplate jdbc;

    public CandidateDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Full replace of the candidate store from a fresh precompute pass (one batch insert). */
    public int replaceAll(Map<Long, List<RecItem>> byUser) {
        jdbc.update("TRUNCATE recommendation_candidates");
        List<Object[]> rows = new ArrayList<>();
        for (var entry : byUser.entrySet()) {
            long userId = entry.getKey();
            List<RecItem> items = entry.getValue();
            for (int rank = 0; rank < items.size(); rank++) {
                RecItem it = items.get(rank);
                rows.add(new Object[]{userId, it.videoId(), it.score(), rank, it.reason()});
            }
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT INTO recommendation_candidates (user_id, video_id, score, rank, reason)
                    VALUES (?, ?, ?, ?, ?)
                    """, rows);
        }
        return rows.size();
    }

    /** Read a user's precomputed candidates (serving path); empty if none precomputed yet. */
    public List<RecItem> findByUser(long userId, int limit) {
        return jdbc.query("""
                SELECT video_id, score, reason FROM recommendation_candidates
                WHERE user_id = ? ORDER BY rank LIMIT ?
                """,
                (rs, i) -> new RecItem(rs.getLong("video_id"), rs.getDouble("score"), rs.getString("reason")),
                userId, limit);
    }
}
