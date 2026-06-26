package com.streamflix.video.repository;

import com.streamflix.video.domain.WatchEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchEventRepository extends JpaRepository<WatchEvent, Long> {
}
