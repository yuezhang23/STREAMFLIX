package com.streamflix.video.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Loads the video catalog from a curated YouTube seed (classpath:catalog/youtube-seed.json) on first
 * boot. Videos are metadata only — each row references a real YouTube video by id; the thumbnail is
 * derived from that id. Runs before {@link BehaviorSeeder} (Order 1) so behavior has a catalog to
 * reference. No-ops if the catalog already exists.
 *
 * <p>Optional enrichment: if {@code YOUTUBE_API_KEY} is set, {@link com.streamflix.video.youtube.YoutubeApiCatalogLoader}
 * refreshes titles/durations from the YouTube Data API; otherwise the JSON values are used verbatim.</p>
 */
@Component
@Order(1)
public class CatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeeder.class);

    public record SeedVideo(String youtubeId, String title, String genre, String channel, int durationSec) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public CatalogSeeder(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM videos", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        List<SeedVideo> seed;
        try (InputStream in = new ClassPathResource("catalog/youtube-seed.json").getInputStream()) {
            seed = List.of(mapper.readValue(in, SeedVideo[].class));
        }
        for (SeedVideo v : seed) {
            jdbc.update("""
                    INSERT INTO videos (youtube_id, title, description, genre, channel, duration_sec, release_year, thumbnail_url)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    v.youtubeId(), v.title(), v.channel() + " — " + v.genre(), v.genre(),
                    v.channel(), v.durationSec(), 2015,
                    "https://img.youtube.com/vi/" + v.youtubeId() + "/hqdefault.jpg");
        }
        log.info("CatalogSeeder loaded {} YouTube videos into the catalog", seed.size());
    }
}
