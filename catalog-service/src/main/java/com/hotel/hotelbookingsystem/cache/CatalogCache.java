package com.hotel.hotelbookingsystem.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class CatalogCache {
    private static final Logger log = LoggerFactory.getLogger(CatalogCache.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String prefix;
    private final long ttlSeconds;
    private final Object[] locks = new Object[64];

    public CatalogCache(StringRedisTemplate redis, ObjectMapper json,
                        @Value("${hotel.cache.prefix}") String prefix,
                        @Value("${hotel.cache.ttl-seconds}") long ttlSeconds) {
        if (ttlSeconds < 1) throw new IllegalArgumentException("Cache TTL must be positive");
        this.redis = redis;
        this.json = json;
        this.prefix = prefix;
        this.ttlSeconds = ttlSeconds;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    public <T> T get(String key, Class<T> type, Supplier<T> loader) {
        return load(prefix + key, json.constructType(type), loader);
    }

    public <T> List<T> list(String key, Class<T> type, Supplier<List<T>> loader) {
        return load(prefix + key, json.getTypeFactory().constructCollectionType(List.class, type), loader);
    }

    private <T> T load(String key, JavaType type, Supplier<T> loader) {
        synchronized (locks[Math.floorMod(key.hashCode(), locks.length)]) {
            String value = read(key);
            if (value != null) {
                try {
                    return json.readValue(value, type);
                } catch (JsonProcessingException e) {
                    log.debug("Ignoring invalid catalog cache entry {}", key);
                }
            }
            T result = loader.get();
            try {
                long seconds = result == null ? 30 : ttlSeconds + ThreadLocalRandom.current().nextInt(31);
                redis.opsForValue().set(key, json.writeValueAsString(result), Duration.ofSeconds(seconds));
            } catch (DataAccessException | JsonProcessingException e) {
                log.debug("Catalog cache write unavailable");
            }
            return result;
        }
    }

    private String read(String key) {
        try {
            return redis.opsForValue().get(key);
        } catch (DataAccessException e) {
            log.debug("Catalog cache unavailable, loading from database");
            return null;
        }
    }
}
