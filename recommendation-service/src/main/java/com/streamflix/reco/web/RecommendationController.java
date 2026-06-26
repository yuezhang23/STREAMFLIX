package com.streamflix.reco.web;

import com.streamflix.reco.dto.RecItem;
import com.streamflix.reco.service.RecommendationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/recommendations")
public class RecommendationController {

    private final RecommendationService service;
    private final int defaultLimit;

    public RecommendationController(RecommendationService service,
                                   @Value("${app.reco.default-limit:10}") int defaultLimit) {
        this.service = service;
        this.defaultLimit = defaultLimit;
    }

    @GetMapping("/{userId}")
    public List<RecItem> forUser(@PathVariable long userId,
                                 @RequestParam(required = false) Integer limit) {
        return service.recommend(userId, limit == null ? defaultLimit : limit);
    }
}
