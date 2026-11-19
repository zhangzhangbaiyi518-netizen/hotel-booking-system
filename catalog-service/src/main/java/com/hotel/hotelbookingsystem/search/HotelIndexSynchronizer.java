package com.hotel.hotelbookingsystem.search;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HotelIndexSynchronizer {
    private static final Logger log = LoggerFactory.getLogger(HotelIndexSynchronizer.class);
    private final HotelSearchDatabase database;
    private final ElasticsearchHotelIndex index;
    private final RedissonClient redis;
    private final boolean enabled;
    private final String prefix;
    public HotelIndexSynchronizer(HotelSearchDatabase database, ElasticsearchHotelIndex index, @Lazy RedissonClient redis,
            @Value("${hotel.search.enabled}") boolean enabled, @Value("${hotel.search.lock-prefix}") String prefix) {
        this.database = database; this.index = index; this.redis = redis; this.enabled = enabled; this.prefix = prefix;
    }
    @Scheduled(fixedDelayString = "${hotel.search.sync-interval-ms}", initialDelay = 1000)
    public void synchronize() {
        if (!enabled) return;
        RLock lock = null;
        boolean acquired = false;
        try {
            lock = redis.getLock(prefix + index.alias());
            acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
            if (acquired) index.synchronize(database.snapshot());
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        catch (Exception exception) { log.warn("Hotel search index pending synchronization: {}", exception.toString()); }
        finally {
            if (acquired) {
                try { lock.unlock(); }
                catch (RuntimeException exception) { log.warn("Hotel search index lock release failed: {}", exception.toString()); }
            }
        }
    }
}
