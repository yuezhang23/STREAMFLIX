package com.streamflix.reco.job;

import com.streamflix.reco.dto.RecItem;
import com.streamflix.reco.engine.CollaborativeFilteringEngine;
import com.streamflix.reco.repository.CandidateDao;
import com.streamflix.reco.repository.InteractionDao;
import com.streamflix.reco.service.CacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Offline candidate generation. On a fixed schedule (and on demand) it builds the CF model once,
 * computes top-N candidates for every active user (plus the demo users), writes them to the durable
 * {@code recommendation_candidates} store, and warms the Redis serving cache. This is what makes the
 * online serving path a fast lookup rather than an on-request computation.
 */
@Component
public class CandidatePrecomputeJob {

    private static final Logger log = LoggerFactory.getLogger(CandidatePrecomputeJob.class);
    private static final int CANDIDATE_LIMIT = 50;
    private static final List<Long> DEMO_USERS = List.of(1L, 2L, 3L, 4L, 5L);

    private final InteractionDao interactions;
    private final CollaborativeFilteringEngine engine;
    private final CandidateDao candidates;
    private final CacheService cache;

    public CandidatePrecomputeJob(InteractionDao interactions, CollaborativeFilteringEngine engine,
                                  CandidateDao candidates, CacheService cache) {
        this.interactions = interactions;
        this.engine = engine;
        this.candidates = candidates;
        this.cache = cache;
    }

    @Scheduled(fixedDelayString = "${app.precompute.interval-ms:30000}", initialDelay = 15000)
    public void scheduled() {
        try {
            runPrecompute();
        } catch (Exception e) {
            log.warn("precompute pass failed: {}", e.getMessage());
        }
    }

    public synchronized int runPrecompute() {
        Set<Long> users = new HashSet<>(interactions.activeUserIds());
        users.addAll(DEMO_USERS);
        Map<Long, List<RecItem>> recs = engine.recommendForUsers(users, CANDIDATE_LIMIT);
        int rows = candidates.replaceAll(recs);
        recs.forEach((userId, list) -> cache.put("reco:" + userId, list));
        log.info("precomputed {} candidate rows for {} users", rows, users.size());
        return rows;
    }
}
