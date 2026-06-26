package com.streamflix.video.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Cache-aside helper over Redis. Values are stored as explicit JSON strings and read back with a
 * declared target type — no polymorphic type-info magic, so records and generic wrappers
 * (e.g. PageResponse&lt;VideoResponse&gt;) round-trip reliably.
 *
 * <p>Honors {@code app.cache.enabled} so the benchmark can compare cache-on vs cache-off latency on
 * identical code paths, and tracks hit/miss counters.</p>
 */
@Service
public class CacheService {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final boolean enabled;
    private final Duration ttl;

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public CacheService(StringRedisTemplate redis,
                        @Value("${app.cache.enabled:true}") boolean enabled,
                        @Value("${app.cache.ttl-seconds:300}") long ttlSeconds) {
        this.redis = redis;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public <T> T getOrLoad(String key, Class<T> type, Supplier<T> loader) {
        return getOrLoad(key, mapper.getTypeFactory().constructType(type), loader);
    }

    public <T> T getOrLoad(String key, JavaType type, Supplier<T> loader) {
        if (!enabled) {
            return loader.get();
        }
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            hits.incrementAndGet();
            return read(cached, type);
        }
        misses.incrementAndGet();
        T value = loader.get();
        if (value != null) {
            redis.opsForValue().set(key, write(value), ttl);
        }
        return value;
    }

    public void evict(String key) {
        redis.delete(key);
    }

    public JavaType parametricType(Class<?> raw, Class<?>... params) {
        return mapper.getTypeFactory().constructParametricType(raw, params);
    }

    public long hits() { return hits.get(); }
    public long misses() { return misses.get(); }

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
