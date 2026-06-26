package com.streamflix.video.service;

import com.streamflix.common.event.BehaviorType;
import com.streamflix.video.domain.Rating;
import com.streamflix.video.domain.WatchEvent;
import com.streamflix.video.event.BehaviorEventPublisher;
import com.streamflix.video.repository.RatingRepository;
import com.streamflix.video.repository.WatchEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Write path for user behavior. Each action is persisted to the OLTP source of truth AND
 * published to Kafka, so downstream services (recommendation, analytics) react via the event
 * backbone rather than reaching into this service's tables.
 */
@Service
public class BehaviorService {

    private final WatchEventRepository watchEvents;
    private final RatingRepository ratings;
    private final BehaviorEventPublisher publisher;

    public BehaviorService(WatchEventRepository watchEvents, RatingRepository ratings,
                           BehaviorEventPublisher publisher) {
        this.watchEvents = watchEvents;
        this.ratings = ratings;
        this.publisher = publisher;
    }

    public void view(Long userId, Long videoId) {
        publisher.publish(userId, videoId, BehaviorType.VIEW, 1);
    }

    @Transactional
    public void watch(Long userId, Long videoId, int watchedSec) {
        watchEvents.save(new WatchEvent(userId, videoId, watchedSec, Instant.now()));
        publisher.publish(userId, videoId, BehaviorType.WATCH, watchedSec);
    }

    @Transactional
    public void rate(Long userId, Long videoId, int rating) {
        Rating existing = ratings.findByUserIdAndVideoId(userId, videoId).orElse(null);
        if (existing == null) {
            ratings.save(new Rating(userId, videoId, rating, Instant.now()));
        } else {
            existing.setRating(rating);
        }
        publisher.publish(userId, videoId, BehaviorType.RATE, rating);
    }

    public void like(Long userId, Long videoId) {
        publisher.publish(userId, videoId, BehaviorType.LIKE, 1);
    }
}
