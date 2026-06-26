package com.streamflix.video.repository;

import com.streamflix.video.domain.Video;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VideoRepository extends JpaRepository<Video, Long> {
    Page<Video> findByGenreIgnoreCase(String genre, Pageable pageable);
    Page<Video> findByTitleContainingIgnoreCase(String title, Pageable pageable);
}
