package com.streamflix.reco.engine;

import com.streamflix.reco.dto.RecItem;
import com.streamflix.reco.repository.InteractionDao;
import com.streamflix.reco.repository.InteractionDao.Interaction;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Item-item collaborative filtering with ranking heuristics.
 *
 * <p>From the user-item interaction matrix we build item vectors (item -> {user -> score}) and
 * score each candidate video for a target user as the cosine-similarity-weighted sum of the user's
 * interactions: {@code score(c) = Σ_i cosine(i, c) · userScore(i)}. The collaborative-filtering
 * signal is then blended with a popularity prior (a ranking heuristic) so sparse users still get
 * sensible results, items the user already engaged with are excluded, and a cold-start user with no
 * history falls back to the most popular titles.</p>
 *
 * <p>At demo scale (tens of users/items) recomputing per request is cheap and clear; the result is
 * cached in Redis, which is where the latency win comes from. At production scale the item-item
 * similarities would be precomputed offline.</p>
 */
@Component
public class CollaborativeFilteringEngine {

    private static final double POPULARITY_WEIGHT = 0.5;

    private final InteractionDao dao;

    public CollaborativeFilteringEngine(InteractionDao dao) {
        this.dao = dao;
    }

    public List<RecItem> recommend(long userId, int limit) {
        List<Interaction> all = dao.findAll();

        Map<Long, Map<Long, Double>> userItems = new HashMap<>();
        Map<Long, Map<Long, Double>> itemUsers = new HashMap<>();
        Map<Long, Double> popularity = new HashMap<>();
        for (Interaction in : all) {
            userItems.computeIfAbsent(in.userId(), k -> new HashMap<>()).put(in.videoId(), in.score());
            itemUsers.computeIfAbsent(in.videoId(), k -> new HashMap<>()).put(in.userId(), in.score());
            popularity.merge(in.videoId(), in.score(), Double::sum);
        }

        double maxPop = popularity.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        Map<Long, Double> itemNorm = new HashMap<>();
        for (var e : itemUsers.entrySet()) {
            itemNorm.put(e.getKey(), Math.sqrt(e.getValue().values().stream()
                    .mapToDouble(s -> s * s).sum()));
        }

        Map<Long, Double> seen = userItems.getOrDefault(userId, Map.of());

        // Cold start: no history -> most popular titles.
        if (seen.isEmpty()) {
            return topPopular(popularity, Set.of(), limit, "Popular on StreamFlix");
        }

        List<RecItem> scored = new ArrayList<>();
        for (Long candidate : itemUsers.keySet()) {
            if (seen.containsKey(candidate)) {
                continue; // exclude already-engaged items
            }
            double cf = 0.0;
            for (var watched : seen.entrySet()) {
                cf += cosine(itemUsers, itemNorm, watched.getKey(), candidate) * watched.getValue();
            }
            double popNorm = popularity.getOrDefault(candidate, 0.0) / maxPop;
            double blended = cf + POPULARITY_WEIGHT * popNorm;
            String reason = cf > 0 ? "Because you watched similar titles" : "Popular on StreamFlix";
            scored.add(new RecItem(candidate, round(blended), reason));
        }

        scored.sort(Comparator.comparingDouble(RecItem::score).reversed());
        if (scored.size() >= limit) {
            return scored.subList(0, limit);
        }
        // Backfill with popular items not already chosen.
        Set<Long> chosen = new HashSet<>(seen.keySet());
        scored.forEach(r -> chosen.add(r.videoId()));
        scored.addAll(topPopular(popularity, chosen, limit - scored.size(), "Popular on StreamFlix"));
        return scored;
    }

    private List<RecItem> topPopular(Map<Long, Double> popularity, Set<Long> exclude, int limit, String reason) {
        return popularity.entrySet().stream()
                .filter(e -> !exclude.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new RecItem(e.getKey(), round(e.getValue()), reason))
                .toList();
    }

    private double cosine(Map<Long, Map<Long, Double>> itemUsers, Map<Long, Double> itemNorm,
                          long a, long b) {
        double na = itemNorm.getOrDefault(a, 0.0);
        double nb = itemNorm.getOrDefault(b, 0.0);
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        Map<Long, Double> va = itemUsers.get(a);
        Map<Long, Double> vb = itemUsers.get(b);
        // iterate the smaller vector
        if (va.size() > vb.size()) {
            Map<Long, Double> tmp = va; va = vb; vb = tmp;
        }
        double dot = 0.0;
        for (var e : va.entrySet()) {
            Double other = vb.get(e.getKey());
            if (other != null) {
                dot += e.getValue() * other;
            }
        }
        return dot / (na * nb);
    }

    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
