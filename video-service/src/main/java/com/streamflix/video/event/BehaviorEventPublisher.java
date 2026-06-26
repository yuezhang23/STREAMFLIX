package com.streamflix.video.event;

import com.streamflix.common.event.BehaviorType;
import com.streamflix.common.event.UserBehaviorEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Publishes user-behavior events to the Kafka {@code user-behavior} topic. The message key is
 * the user id, so all events for a user land on the same partition (per-user ordering) and the
 * topic can be partitioned to scale consumers horizontally.
 */
@Component
public class BehaviorEventPublisher {

    private final KafkaTemplate<String, UserBehaviorEvent> kafka;

    public BehaviorEventPublisher(KafkaTemplate<String, UserBehaviorEvent> kafka) {
        this.kafka = kafka;
    }

    public void publish(Long userId, Long videoId, BehaviorType type, double value) {
        UserBehaviorEvent event = new UserBehaviorEvent(
                UUID.randomUUID().toString(), userId, videoId, type, value, Instant.now());
        kafka.send(UserBehaviorEvent.TOPIC, String.valueOf(userId), event);
    }
}
