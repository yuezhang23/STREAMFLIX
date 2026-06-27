package com.streamflix.video.config;

import com.streamflix.video.domain.Video;
import com.streamflix.video.repository.VideoRepository;
import com.streamflix.video.service.BehaviorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Simulates continuous live traffic: each tick emits a batch of behavior events for random users
 * over genre-coherent videos, through {@link BehaviorService} (OLTP + Kafka). This keeps the
 * asynchronous pipeline continuously processing thousands of watch events and lets trending and
 * recommendations evolve over time. Toggle with {@code app.traffic.enabled}.
 */
@Component
public class TrafficSimulator {

    private static final Logger log = LoggerFactory.getLogger(TrafficSimulator.class);

    private final VideoRepository videos;
    private final BehaviorService behavior;
    private final boolean enabled;
    private final int eventsPerTick;
    private final int users;

    private final Random rnd = new Random();
    private final AtomicLong totalEmitted = new AtomicLong();
    private volatile List<Video> catalog = List.of();
    private volatile Map<String, List<Video>> byGenre = Map.of();
    private volatile List<String> genres = List.of();

    public TrafficSimulator(VideoRepository videos, BehaviorService behavior,
                            @Value("${app.traffic.enabled:true}") boolean enabled,
                            @Value("${app.traffic.events-per-tick:20}") int eventsPerTick,
                            @Value("${app.traffic.users:400}") int users) {
        this.videos = videos;
        this.behavior = behavior;
        this.enabled = enabled;
        this.eventsPerTick = eventsPerTick;
        this.users = users;
    }

    @Scheduled(fixedDelayString = "${app.traffic.interval-ms:5000}", initialDelay = 20000)
    public void tick() {
        if (!enabled) {
            return;
        }
        if (catalog.isEmpty()) {
            catalog = videos.findAll();
            byGenre = catalog.stream().collect(Collectors.groupingBy(Video::getGenre));
            genres = List.copyOf(byGenre.keySet());
            if (catalog.isEmpty()) {
                return;
            }
        }
        for (int i = 0; i < eventsPerTick; i++) {
            long userId = 1 + rnd.nextInt(users);
            // each user leans toward one genre (stable per user via modulo) with some exploration
            String genre = genres.get((int) (userId % genres.size()));
            List<Video> pool = rnd.nextDouble() < 0.75 ? byGenre.get(genre) : catalog;
            Video v = pool.get(rnd.nextInt(pool.size()));
            emitRandomEvent(userId, v);
        }
        long total = totalEmitted.addAndGet(eventsPerTick);
        if (total % 200 == 0) {
            log.info("TrafficSimulator has emitted {} synthetic events", total);
        }
    }

    private void emitRandomEvent(long userId, Video v) {
        double r = rnd.nextDouble();
        if (r < 0.6) {
            behavior.watch(userId, v.getId(), (int) (v.getDurationSec() * (0.3 + rnd.nextDouble() * 0.7)));
        } else if (r < 0.8) {
            behavior.view(userId, v.getId());
        } else if (r < 0.93) {
            behavior.rate(userId, v.getId(), 3 + rnd.nextInt(3)); // 3..5
        } else {
            behavior.like(userId, v.getId());
        }
    }
}
