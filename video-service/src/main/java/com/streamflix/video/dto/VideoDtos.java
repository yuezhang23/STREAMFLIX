package com.streamflix.video.dto;

import com.streamflix.video.domain.Video;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/** API payloads for the video catalog. Records are JSON-serializable for Redis caching. */
public final class VideoDtos {

    public record VideoResponse(
            Long id, String youtubeId, String title, String description, String genre,
            String channel, int durationSec, int releaseYear, String thumbnailUrl) {

        public static VideoResponse from(Video v) {
            return new VideoResponse(v.getId(), v.getYoutubeId(), v.getTitle(), v.getDescription(),
                    v.getGenre(), v.getChannel(), v.getDurationSec(), v.getReleaseYear(),
                    v.getThumbnailUrl());
        }
    }

    /** Cache-friendly page wrapper (Spring's Page is not stably (de)serializable). */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    /**
     * A genre "tag" for the homepage chip bar. {@code personalized} is true when the score is
     * derived from this user's own behavior (vs. a cold-start global-popularity placeholder).
     */
    public record GenreTag(String genre, double score, boolean personalized) {
    }

    public record RateRequest(@Min(1) @Max(5) int rating) {
    }

    public record WatchRequest(@Min(0) int watchedSec) {
    }

    private VideoDtos() {
    }
}
