package com.streamflix.video.service;

import com.streamflix.video.domain.Video;
import com.streamflix.video.dto.VideoDtos.GenreTag;
import com.streamflix.video.dto.VideoDtos.PageResponse;
import com.streamflix.video.dto.VideoDtos.VideoResponse;
import com.streamflix.video.repository.TagDao;
import com.streamflix.video.repository.VideoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Read path for the catalog. Both hot reads are served cache-aside from Redis so repeat
 * traffic skips Postgres entirely.
 */
@Service
public class VideoService {

    private final VideoRepository repository;
    private final CacheService cache;
    private final TagDao tagDao;

    public VideoService(VideoRepository repository, CacheService cache, TagDao tagDao) {
        this.repository = repository;
        this.cache = cache;
        this.tagDao = tagDao;
    }

    public VideoResponse getById(Long id) {
        return cache.getOrLoad("video:" + id, VideoResponse.class, () -> repository.findById(id)
                .map(VideoResponse::from)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Video not found")));
    }

    public PageResponse<VideoResponse> list(String genre, int page, int size) {
        String key = "videos:genre:" + (genre == null ? "all" : genre.toLowerCase())
                + ":page:" + page + ":size:" + size;
        return cache.getOrLoad(key, cache.parametricType(PageResponse.class, VideoResponse.class), () -> {
            Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
            Page<Video> result = (genre == null || genre.isBlank())
                    ? repository.findAll(pageable)
                    : repository.findByGenreIgnoreCase(genre, pageable);
            return toPageResponse(result);
        });
    }

    /** Search is intentionally uncached (high-cardinality queries). */
    public PageResponse<VideoResponse> search(String q, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return toPageResponse(repository.findByTitleContainingIgnoreCase(q == null ? "" : q, pageable));
    }

    /**
     * Ranked genre "tags" for the homepage chip bar. Genres the user has interacted with come
     * first (score-descending, {@code personalized=true}); the remaining catalog genres follow in
     * global-popularity order ({@code score=0}, {@code personalized=false}) so every type is always
     * browsable. A brand-new user (no history) gets the pure popularity ordering — the "no data
     * yet" state. As the user watches/rates, their preferred genres surface to the front.
     */
    public List<GenreTag> recommendedTags(long userId) {
        Map<String, Double> userScores = tagDao.userGenreScores(userId);
        List<GenreTag> tags = new ArrayList<>();
        // personalized genres first, in the score-descending order the query returned
        for (Map.Entry<String, Double> e : userScores.entrySet()) {
            tags.add(new GenreTag(e.getKey(), Math.round(e.getValue() * 10.0) / 10.0, true));
        }
        // then any untouched catalog genres, by global popularity
        for (String genre : tagDao.genresByPopularity()) {
            if (!userScores.containsKey(genre)) {
                tags.add(new GenreTag(genre, 0.0, false));
            }
        }
        return tags;
    }

    private PageResponse<VideoResponse> toPageResponse(Page<Video> result) {
        return new PageResponse<>(
                result.map(VideoResponse::from).getContent(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
