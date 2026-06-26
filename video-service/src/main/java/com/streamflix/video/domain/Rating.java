package com.streamflix.video.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "ratings", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "video_id"}))
public class Rating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "video_id", nullable = false)
    private Long videoId;

    @Column(nullable = false)
    private int rating;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Rating() {
    }

    public Rating(Long userId, Long videoId, int rating, Instant createdAt) {
        this.userId = userId;
        this.videoId = videoId;
        this.rating = rating;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getVideoId() { return videoId; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public Instant getCreatedAt() { return createdAt; }
}
