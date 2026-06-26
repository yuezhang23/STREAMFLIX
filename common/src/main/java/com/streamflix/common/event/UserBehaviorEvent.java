package com.streamflix.common.event;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * Canonical user-behavior event flowing through the Kafka {@code user-behavior} topic.
 *
 * <p>Produced by video-service on every catalog interaction; consumed independently by
 * recommendation-service (consumer group {@code reco}) and analytics-service
 * (consumer group {@code analytics}).</p>
 *
 * <p>{@code eventId} is a producer-generated UUID enabling idempotent consumption under
 * Kafka at-least-once delivery semantics. {@code value} carries the type-specific payload
 * (seconds watched for WATCH, rating 1-5 for RATE, otherwise 1).</p>
 */
public record UserBehaviorEvent(
        String eventId,
        Long userId,
        Long videoId,
        BehaviorType type,
        double value,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant timestamp
) {
    public static final String TOPIC = "user-behavior";
}
