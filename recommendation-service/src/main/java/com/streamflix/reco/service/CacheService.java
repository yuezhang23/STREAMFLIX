package com.streamflix.reco.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache-aside helper for the expensive recommendation compute. Collaborative-filtering scoring over
 * the interaction matrix is the costly path; caching the per-user result in Redis is what produces
 * the latency reduction measured by the benchmark. Values are stored as explicit JSON (typed reads),
 * and {@code app.cache.enabled} lets the benchmark compare identical code paths cache-on vs off.
 */
@Service
public class CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final boolean enabled;
    private final Duration ttl;

    public CacheService(StringRedisTemplate redis,
                        @Value("${app.cache.enabled:true}") boolean enabled,
                        @Value("${app.cache.ttl-seconds:120}") long ttlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public <T> T getOrLoad(String key, JavaType type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return read(cached, type);
        }
        T value = loader.get();
        if (value != null) {
            redis.opsForValue().set(key, write(value), ttl);
        }
        return value;
    }

    public void evict(String key) {
        redis.delete(key);
    }

    /** Proactively warm the cache (used by the precompute job). */
    public void put(String key, Object value) {
        if (enabled && value != null) {
            redis.opsForValue().set(key, write(value), ttl);
        }
    }

    public JavaType listType(Class<?> element) {
        return mapper.getTypeFactory().constructCollectionType(java.util.List.class, element);
    }

    private <T> T read(String json, JavaType type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cache deserialization failed", e);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cache serialization failed", e);
        }
    }
}
