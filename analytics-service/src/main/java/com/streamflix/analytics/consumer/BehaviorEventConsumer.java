package com.streamflix.analytics.consumer;

import com.streamflix.common.event.UserBehaviorEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/**
 * Consumes the {@code user-behavior} topic (group {@code analytics}) and appends each event to
 * the OLTP raw event store. INSERT ... ON CONFLICT DO NOTHING on event_id makes consumption
 * idempotent under Kafka's at-least-once delivery.
 */
@Component
public class BehaviorEventConsumer {

    private final JdbcTemplate oltp;

    public BehaviorEventConsumer(@Qualifier("oltpJdbcTemplate") JdbcTemplate oltp) {
        this.oltp = oltp;
    }

    @KafkaListener(topics = UserBehaviorEvent.TOPIC, groupId = "analytics")
    public void onEvent(UserBehaviorEvent event) {
        oltp.update("""
                INSERT INTO analytics_raw_events (event_id, user_id, video_id, type, value, ts)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """,
                event.eventId(), event.userId(), event.videoId(),
                event.type().name(), event.value(), Timestamp.from(event.timestamp()));
    }
}
