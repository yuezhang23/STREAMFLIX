package com.streamflix.analytics.service;

import com.streamflix.analytics.dto.AnalyticsDtos.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Read side over the OLAP star schema. */
@Service
public class AnalyticsQueryService {

    private final JdbcTemplate olap;

    public AnalyticsQueryService(@Qualifier("olapJdbcTemplate") JdbcTemplate olap) {
        this.olap = olap;
    }

    public List<TrendingItem> trending(int limit) {
        return olap.query(
                "SELECT video_id, score FROM agg_trending ORDER BY score DESC LIMIT ?",
                (rs, i) -> new TrendingItem(rs.getLong("video_id"), rs.getDouble("score")),
                limit);
    }

    public VideoStats videoStats(Long videoId) {
        List<VideoStats> rows = olap.query(
                """
                SELECT video_id, total_views, unique_viewers, total_watch_sec, avg_rating, ratings_count
                FROM agg_video_stats WHERE video_id = ?
                """,
                (rs, i) -> new VideoStats(
                        rs.getLong("video_id"), rs.getLong("total_views"), rs.getLong("unique_viewers"),
                        rs.getLong("total_watch_sec"),
                        rs.getObject("avg_rating") == null ? null : rs.getDouble("avg_rating"),
                        rs.getLong("ratings_count")),
                videoId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "No stats yet for video " + videoId);
        }
        return rows.get(0);
    }

    public Overview overview() {
        Long totalEvents = olap.queryForObject(
                "SELECT COALESCE(sum(views),0) FROM fact_views", Long.class);
        Long videosWithViews = olap.queryForObject(
                "SELECT count(*) FROM agg_video_stats WHERE total_views > 0", Long.class);
        Long distinctUsers = olap.queryForObject(
                "SELECT COALESCE(max(unique_viewers),0) FROM agg_video_stats", Long.class);
        Long totalWatchHours = olap.queryForObject(
                "SELECT COALESCE(sum(total_watch_sec),0)/3600 FROM agg_video_stats", Long.class);
        return new Overview(
                totalEvents == null ? 0 : totalEvents,
                videosWithViews == null ? 0 : videosWithViews,
                distinctUsers == null ? 0 : distinctUsers,
                totalWatchHours == null ? 0 : totalWatchHours);
    }
}
