package com.streamflix.video.config;

import com.streamflix.video.domain.Video;
import com.streamflix.video.repository.VideoRepository;
import com.streamflix.video.repository.WatchEventRepository;
import com.streamflix.video.service.BehaviorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Generates correlated synthetic watch/rating behavior on first boot: each synthetic user is
 * assigned a preferred genre (derived from the actual catalog), watches/rates videos in it heavily,
 * plus some cross-genre noise. Every action goes through {@link BehaviorService}, so it is persisted
 * to OLTP AND published to Kafka — the recommendation and analytics services bootstrap from real
 * signal via the event backbone, exactly as for live traffic. Runs after {@link CatalogSeeder}
 * (Order 2). No-ops if behavior already exists.
 */
@Component
@Order(2)
public class BehaviorSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BehaviorSeeder.class);
    private static final int SYNTHETIC_USERS = 400;

    private final VideoRepository videos;
    private final WatchEventRepository watchEvents;
    private final BehaviorService behavior;

    public BehaviorSeeder(VideoRepository videos, WatchEventRepository watchEvents, BehaviorService behavior) {
        this.videos = videos;
        this.watchEvents = watchEvents;
        this.behavior = behavior;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (watchEvents.count() > 0) {
            return;
        }
        List<Video> catalog = videos.findAll();
        if (catalog.isEmpty()) {
            return;
        }
        // Preferred genres are derived from the actual catalog, so this works for any seed.
        List<String> genres = catalog.stream().map(Video::getGenre).distinct().sorted().toList();
        Random rnd = new Random(42);
        int events = 0;
        for (long userId = 1; userId <= SYNTHETIC_USERS; userId++) {
            String preferred = genres.get((int) (userId % genres.size()));
            for (Video v : catalog) {
                boolean isPreferred = v.getGenre().equalsIgnoreCase(preferred);
                if (isPreferred) {
                    int watched = (int) (v.getDurationSec() * (0.6 + rnd.nextDouble() * 0.4));
                    behavior.watch(userId, v.getId(), watched);
                    events++;
                    if (rnd.nextDouble() < 0.7) {
                        behavior.rate(userId, v.getId(), 4 + rnd.nextInt(2)); // 4..5
                        events++;
                    }
                } else if (rnd.nextDouble() < 0.12) {
                    int watched = (int) (v.getDurationSec() * (0.1 + rnd.nextDouble() * 0.5));
                    behavior.watch(userId, v.getId(), watched);
                    events++;
                    if (rnd.nextDouble() < 0.3) {
                        behavior.rate(userId, v.getId(), 2 + rnd.nextInt(2)); // 2..3
                        events++;
                    }
                }
            }
        }
        log.info("BehaviorSeeder published {} synthetic behavior events to Kafka", events);
    }
}
