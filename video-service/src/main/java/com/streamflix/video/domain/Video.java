package com.streamflix.video.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "videos")
public class Video {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private String genre;

    @Column(name = "duration_sec", nullable = false)
    private int durationSec;

    @Column(name = "release_year", nullable = false)
    private int releaseYear;

    @Column(name = "thumbnail_url")
    private String thumbnailUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Video() {
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getGenre() { return genre; }
    public int getDurationSec() { return durationSec; }
    public int getReleaseYear() { return releaseYear; }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
