package com.streamflix.analytics.dto;

public final class AnalyticsDtos {

    public record TrendingItem(Long videoId, double score) {
    }

    public record VideoStats(Long videoId, long totalViews, long uniqueViewers,
                             long totalWatchSec, Double avgRating, long ratingsCount) {
    }

    public record Overview(long totalEvents, long videosWithViews, long distinctUsers,
                           long totalWatchHours) {
    }

    private AnalyticsDtos() {
    }
}
