package com.streamflix.reco.consumer;

import com.streamflix.common.event.BehaviorType;
import com.streamflix.common.event.UserBehaviorEvent;
import com.streamflix.reco.repository.InteractionDao;
import com.streamflix.reco.service.CacheService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the {@code user-behavior} topic (group {@code reco}) and folds each event into the
 * interaction matrix with a type-specific weight. The affected user's cached recommendations are
 * evicted so the next request recomputes with fresh signal.
 */
@Component
public class BehaviorEventConsumer {

    private final InteractionDao dao;
    private final CacheService cache;

    public BehaviorEventConsumer(InteractionDao dao, CacheService cache) {
        this.dao = dao;
        this.cache = cache;
    }

    @KafkaListener(topics = UserBehaviorEvent.TOPIC, groupId = "reco")
    public void onEvent(UserBehaviorEvent event) {
        double weight = weightOf(event.type(), event.value());
        if (weight <= 0) {
            return;
        }
        dao.addScore(event.userId(), event.videoId(), weight);
        cache.evict("reco:" + event.userId());
    }

    private double weightOf(BehaviorType type, double value) {
        return switch (type) {
            case VIEW -> 0.5;
            case WATCH -> 1.0;
            case LIKE -> 2.0;
            case RATE -> Math.max(0, value - 2); // 3->1, 4->2, 5->3; low ratings contribute nothing
        };
    }
}
