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
 * signal is blended with a popularity prior (a ranking heuristic) so sparse users still get sensible
 * results, items the user already engaged with are excluded, and a cold-start user with no history
 * falls back to the most popular titles.</p>
 *
 * <p>The matrix is loaded into a {@link Model} once; {@link #recommend} scores a single user while
 * {@link #recommendForUsers} scores many users from one model build (used by the precompute job).</p>
 */
@Component
public class CollaborativeFilteringEngine {

    private static final double POPULARITY_WEIGHT = 0.5;

    private final InteractionDao dao;

    public CollaborativeFilteringEngine(InteractionDao dao) {
        this.dao = dao;
    }

    public List<RecItem> recommend(long userId, int limit) {
        return score(buildModel(), userId, limit);
    }

    /** Build the model once and score every requested user — used for batch candidate precompute. */
    public Map<Long, List<RecItem>> recommendForUsers(Collection<Long> userIds, int limit) {
        Model model = buildModel();
        Map<Long, List<RecItem>> out = new HashMap<>();
        for (Long userId : userIds) {
            out.put(userId, score(model, userId, limit));
        }
        return out;
    }

    // ---- model ----

    private static final class Model {
        final Map<Long, Map<Long, Double>> userItems = new HashMap<>();
        final Map<Long, Map<Long, Double>> itemUsers = new HashMap<>();
        final Map<Long, Double> popularity = new HashMap<>();
        final Map<Long, Double> itemNorm = new HashMap<>();
        double maxPop = 1.0;
    }

    private Model buildModel() {
        Model m = new Model();
        for (Interaction in : dao.findAll()) {
            m.userItems.computeIfAbsent(in.userId(), k -> new HashMap<>()).put(in.videoId(), in.score());
            m.itemUsers.computeIfAbsent(in.videoId(), k -> new HashMap<>()).put(in.userId(), in.score());
            m.popularity.merge(in.videoId(), in.score(), Double::sum);
        }
        m.maxPop = m.popularity.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        for (var e : m.itemUsers.entrySet()) {
            m.itemNorm.put(e.getKey(), Math.sqrt(e.getValue().values().stream()
                    .mapToDouble(s -> s * s).sum()));
        }
        return m;
    }

    private List<RecItem> score(Model m, long userId, int limit) {
        Map<Long, Double> seen = m.userItems.getOrDefault(userId, Map.of());

        // Cold start: no history -> most popular titles.
        if (seen.isEmpty()) {
            return topPopular(m, Set.of(), limit, "Popular on StreamFlix");
        }

        List<RecItem> scored = new ArrayList<>();
        for (Long candidate : m.itemUsers.keySet()) {
            if (seen.containsKey(candidate)) {
                continue; // exclude already-engaged items
            }
            double cf = 0.0;
            for (var watched : seen.entrySet()) {
                cf += cosine(m, watched.getKey(), candidate) * watched.getValue();
            }
            double popNorm = m.popularity.getOrDefault(candidate, 0.0) / m.maxPop;
            double blended = cf + POPULARITY_WEIGHT * popNorm;
            String reason = cf > 0 ? "Because you watched similar titles" : "Popular on StreamFlix";
            scored.add(new RecItem(candidate, round(blended), reason));
        }

        scored.sort(Comparator.comparingDouble(RecItem::score).reversed());
        if (scored.size() >= limit) {
            return scored.subList(0, limit);
        }
        Set<Long> chosen = new HashSet<>(seen.keySet());
        scored.forEach(r -> chosen.add(r.videoId()));
        scored.addAll(topPopular(m, chosen, limit - scored.size(), "Popular on StreamFlix"));
        return scored;
    }

    private List<RecItem> topPopular(Model m, Set<Long> exclude, int limit, String reason) {
        return m.popularity.entrySet().stream()
                .filter(e -> !exclude.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> new RecItem(e.getKey(), round(e.getValue()), reason))
                .toList();
    }

    private double cosine(Model m, long a, long b) {
        double na = m.itemNorm.getOrDefault(a, 0.0);
        double nb = m.itemNorm.getOrDefault(b, 0.0);
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        Map<Long, Double> va = m.itemUsers.get(a);
        Map<Long, Double> vb = m.itemUsers.get(b);
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
