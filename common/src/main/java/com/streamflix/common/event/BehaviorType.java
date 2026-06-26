package com.streamflix.common.event;

/**
 * Types of user behavior tracked across the platform. Emitted by video-service
 * and consumed by recommendation-service and analytics-service via Kafka.
 */
public enum BehaviorType {
    VIEW,   // opened a video detail page
    WATCH,  // watched (value = seconds watched)
    RATE,   // rated a video (value = rating 1-5)
    LIKE    // liked a video
}
