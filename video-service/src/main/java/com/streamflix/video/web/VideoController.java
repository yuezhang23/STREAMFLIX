package com.streamflix.video.web;

import com.streamflix.video.dto.VideoDtos.*;
import com.streamflix.video.service.BehaviorService;
import com.streamflix.video.service.VideoService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoService videoService;
    private final BehaviorService behaviorService;

    public VideoController(VideoService videoService, BehaviorService behaviorService) {
        this.videoService = videoService;
        this.behaviorService = behaviorService;
    }

    @GetMapping
    public PageResponse<VideoResponse> list(
            @RequestParam(required = false) String genre,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return videoService.list(genre, page, size);
    }

    @GetMapping("/search")
    public PageResponse<VideoResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return videoService.search(q, page, size);
    }

    /**
     * Ranked genre "tags" for the homepage chip bar. The user id is taken from the path (not the
     * X-User-Id header) because the gateway treats GET /api/videos/** as public catalog browsing
     * and strips identity headers — mirroring the /api/recommendations/{userId} contract.
     */
    @GetMapping("/tags/{userId}")
    public java.util.List<GenreTag> tags(@PathVariable Long userId) {
        return videoService.recommendedTags(userId);
    }

    @GetMapping("/{id}")
    public VideoResponse get(@PathVariable Long id) {
        return videoService.getById(id);
    }

    @PostMapping("/{id}/view")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void view(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId) {
        behaviorService.view(userId, id);
    }

    @PostMapping("/{id}/watch")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void watch(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId,
                      @Valid @RequestBody WatchRequest req) {
        behaviorService.watch(userId, id, req.watchedSec());
    }

    @PostMapping("/{id}/rate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void rate(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId,
                     @Valid @RequestBody RateRequest req) {
        behaviorService.rate(userId, id, req.rating());
    }

    @PostMapping("/{id}/like")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void like(@PathVariable Long id, @RequestHeader("X-User-Id") Long userId) {
        behaviorService.like(userId, id);
    }
}
