package com.streamflix.reco.service;

import com.streamflix.reco.dto.RecItem;
import com.streamflix.reco.engine.CollaborativeFilteringEngine;
import com.streamflix.reco.repository.CandidateDao;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Serves per-user recommendations. The feed is generated as a fast lookup:
 * <b>Redis (warm) → precomputed candidate store → on-the-fly CF compute (cold start)</b>.
 * The precompute job keeps Redis and the candidate store warm, so the expensive collaborative-
 * filtering computation is off the request path for active users.
 */
@Service
public class RecommendationService {

    /** Max list cached/served per user; requests slice from this (so the cache key omits limit). */
    private static final int CACHE_LIMIT = 50;

    private final CollaborativeFilteringEngine engine;
    private final CandidateDao candidates;
    private final CacheService cache;
    /** Benchmark baseline: when true, bypass cache + candidate store and compute every request. */
    private final boolean forceCompute;

    public RecommendationService(CollaborativeFilteringEngine engine, CandidateDao candidates,
                                 CacheService cache,
                                 @Value("${app.reco.force-compute:false}") boolean forceCompute) {
        this.engine = engine;
        this.candidates = candidates;
        this.cache = cache;
        this.forceCompute = forceCompute;
    }

    public List<RecItem> recommend(long userId, int limit) {
        if (forceCompute) {
            return slice(engine.recommend(userId, CACHE_LIMIT), limit);
        }
        List<RecItem> full = cache.getOrLoad("reco:" + userId, cache.listType(RecItem.class),
                () -> loadCandidates(userId));
        return slice(full, limit);
    }

    private List<RecItem> slice(List<RecItem> full, int limit) {
        return full.size() > limit ? List.copyOf(full.subList(0, limit)) : full;
    }

    /** Cache miss: prefer the precomputed candidate store; fall back to computing on the fly. */
    private List<RecItem> loadCandidates(long userId) {
        List<RecItem> precomputed = candidates.findByUser(userId, CACHE_LIMIT);
        if (!precomputed.isEmpty()) {
            return precomputed;
        }
        return engine.recommend(userId, CACHE_LIMIT);
    }
}
