package com.streamflix.video.service;

import com.streamflix.video.domain.Video;
import com.streamflix.video.dto.VideoDtos.PageResponse;
import com.streamflix.video.dto.VideoDtos.VideoResponse;
import com.streamflix.video.repository.VideoRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Read path for the catalog. Both hot reads are served cache-aside from Redis so repeat
 * traffic skips Postgres entirely.
 */
@Service
public class VideoService {

    private final VideoRepository repository;
    private final CacheService cache;

    public VideoService(VideoRepository repository, CacheService cache) {
        this.repository = repository;
        this.cache = cache;
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

    private PageResponse<VideoResponse> toPageResponse(Page<Video> result) {
        return new PageResponse<>(
                result.map(VideoResponse::from).getContent(),
                result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }
}
