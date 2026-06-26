package com.streamflix.reco.service;

import com.streamflix.reco.dto.RecItem;
import com.streamflix.reco.engine.CollaborativeFilteringEngine;
import org.springframework.stereotype.Service;

import java.util.List;

/** Serves per-user recommendations, computing via CF on a cache miss and caching the result. */
@Service
public class RecommendationService {

    private final CollaborativeFilteringEngine engine;
    private final CacheService cache;

    public RecommendationService(CollaborativeFilteringEngine engine, CacheService cache) {
        this.engine = engine;
        this.cache = cache;
    }

    /** Max list we compute+cache per user; requests slice from this (so the cache key omits limit). */
    private static final int CACHE_LIMIT = 50;

    public List<RecItem> recommend(long userId, int limit) {
        List<RecItem> full = cache.getOrLoad("reco:" + userId, cache.listType(RecItem.class),
                () -> engine.recommend(userId, CACHE_LIMIT));
        return full.size() > limit ? List.copyOf(full.subList(0, limit)) : full;
    }
}
