package com.streamflix.analytics.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Batch ETL: reads the OLTP raw event store, computes aggregates, and replaces the OLAP
 * star-schema tables (recompute-and-replace — simple and correct at demo scale). Runs on a
 * fixed interval (near-real-time for the demo; a production batch would run hourly/daily) and
 * can be triggered on demand via the controller.
 */
@Component
public class EtlJob {

    private static final Logger log = LoggerFactory.getLogger(EtlJob.class);

    private final JdbcTemplate oltp;
    private final JdbcTemplate olap;

    public EtlJob(@Qualifier("oltpJdbcTemplate") JdbcTemplate oltp,
                  @Qualifier("olapJdbcTemplate") JdbcTemplate olap) {
        this.oltp = oltp;
        this.olap = olap;
    }

    @Scheduled(fixedDelayString = "${app.etl.interval-ms:30000}", initialDelay = 10000)
    public void scheduled() {
        try {
            runEtl();
        } catch (Exception e) {
            log.warn("ETL run failed: {}", e.getMessage());
        }
    }

    public synchronized int runEtl() {
        List<Map<String, Object>> facts = oltp.queryForList("""
                SELECT (to_char(ts,'YYYYMMDD'))::int AS date_key, ts::date AS full_date, video_id,
                       count(*) FILTER (WHERE type IN ('VIEW','WATCH'))            AS views,
                       COALESCE(sum(value) FILTER (WHERE type='WATCH'), 0)::bigint AS watch_sec
                FROM analytics_raw_events
                GROUP BY 1, 2, video_id
                """);

        List<Map<String, Object>> stats = oltp.queryForList("""
                SELECT video_id,
                       count(*) FILTER (WHERE type IN ('VIEW','WATCH'))               AS total_views,
                       count(DISTINCT user_id) FILTER (WHERE type IN ('VIEW','WATCH')) AS unique_viewers,
                       COALESCE(sum(value) FILTER (WHERE type='WATCH'), 0)::bigint     AS total_watch_sec,
                       avg(value) FILTER (WHERE type='RATE')                          AS avg_rating,
                       count(*) FILTER (WHERE type='RATE')                            AS ratings_count
                FROM analytics_raw_events
                GROUP BY video_id
                """);

        List<Map<String, Object>> trending = oltp.queryForList("""
                SELECT video_id,
                       sum( (CASE type WHEN 'WATCH' THEN 2 WHEN 'LIKE' THEN 3 WHEN 'RATE' THEN value ELSE 1 END)
                            * exp( - (EXTRACT(EPOCH FROM (now() - ts)) / 86400.0) / 7.0 ) ) AS score
                FROM analytics_raw_events
                WHERE ts > now() - interval '14 days'
                GROUP BY video_id
                """);

        // Recompute-and-replace the OLAP tables.
        olap.execute("TRUNCATE dim_date, fact_views, agg_video_stats, agg_trending");

        facts.stream()
                .map(r -> new Object[]{r.get("date_key"), r.get("full_date")})
                .distinct()
                .forEach(d -> olap.update(
                        "INSERT INTO dim_date(date_key, full_date) VALUES (?, ?) ON CONFLICT DO NOTHING",
                        d[0], d[1]));

        for (Map<String, Object> r : facts) {
            olap.update("INSERT INTO fact_views(date_key, video_id, views, watch_sec) VALUES (?,?,?,?)",
                    r.get("date_key"), r.get("video_id"), r.get("views"), r.get("watch_sec"));
        }
        for (Map<String, Object> r : stats) {
            olap.update("""
                    INSERT INTO agg_video_stats(video_id, total_views, unique_viewers, total_watch_sec, avg_rating, ratings_count)
                    VALUES (?,?,?,?,?,?)
                    """,
                    r.get("video_id"), r.get("total_views"), r.get("unique_viewers"),
                    r.get("total_watch_sec"), r.get("avg_rating"), r.get("ratings_count"));
        }
        for (Map<String, Object> r : trending) {
            olap.update("INSERT INTO agg_trending(video_id, score) VALUES (?,?)",
                    r.get("video_id"), r.get("score"));
        }

        log.info("ETL complete: {} daily facts, {} video stats, {} trending rows",
                facts.size(), stats.size(), trending.size());
        return facts.size();
    }
}
