package com.streamflix.video.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "watch_events")
public class WatchEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(name = "watched_sec", nullable = false)
    private int watchedSec;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WatchEvent() {
    }

    public WatchEvent(Long userId, Long videoId, int watchedSec, Instant createdAt) {
        this.userId = userId;
        this.videoId = videoId;
        this.watchedSec = watchedSec;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getVideoId() { return videoId; }
    public int getWatchedSec() { return watchedSec; }
    public Instant getCreatedAt() { return createdAt; }
}
