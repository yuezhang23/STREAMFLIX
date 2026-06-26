package com.streamflix.video.repository;

import com.streamflix.video.domain.Rating;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long> {
    Optional<Rating> findByUserIdAndVideoId(Long userId, Long videoId);
}
