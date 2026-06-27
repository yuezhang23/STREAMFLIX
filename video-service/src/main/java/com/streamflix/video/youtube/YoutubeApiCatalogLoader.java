package com.streamflix.video.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional backup metadata source. When {@code YOUTUBE_API_KEY} is set, refreshes the seeded
 * catalog's titles/channels from the YouTube Data API v3 (best-effort; failures are logged and
 * ignored so the demo never depends on the API). When the key is absent (default), this is a no-op
 * and the curated JSON values are used verbatim. Runs after the catalog is seeded (Order 3).
 */
@Component
@Order(3)
public class YoutubeApiCatalogLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(YoutubeApiCatalogLoader.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String apiKey;

    public YoutubeApiCatalogLoader(JdbcTemplate jdbc, ObjectMapper mapper,
                                   @Value("${youtube.api.key:}") String apiKey) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (apiKey == null || apiKey.isBlank()) {
            return; // default path: curated JSON only
        }
        List<String> ids = jdbc.queryForList(
                "SELECT youtube_id FROM videos WHERE youtube_id IS NOT NULL", String.class);
        if (ids.isEmpty()) {
            return;
        }
        HttpClient http = HttpClient.newHttpClient();
        int updated = 0;
        for (int i = 0; i < ids.size(); i += 50) {
            List<String> batch = ids.subList(i, Math.min(i + 50, ids.size()));
            try {
                updated += refreshBatch(http, batch);
            } catch (Exception e) {
                log.warn("YouTube API refresh failed for a batch (continuing): {}", e.getMessage());
            }
        }
        log.info("YoutubeApiCatalogLoader refreshed {} catalog rows from the YouTube Data API", updated);
    }

    private int refreshBatch(HttpClient http, List<String> ids) throws Exception {
        String url = "https://www.googleapis.com/youtube/v3/videos?part=snippet&id="
                + URLEncoder.encode(String.join(",", ids), StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode items = mapper.readTree(resp.body()).path("items");
        int n = 0;
        for (JsonNode item : items) {
            String id = item.path("id").asText();
            JsonNode snippet = item.path("snippet");
            String title = snippet.path("title").asText(null);
            String channel = snippet.path("channelTitle").asText(null);
            if (title != null) {
                jdbc.update("UPDATE videos SET title = ?, channel = COALESCE(?, channel) WHERE youtube_id = ?",
                        title, channel, id);
                n++;
            }
        }
        return n;
    }
}
