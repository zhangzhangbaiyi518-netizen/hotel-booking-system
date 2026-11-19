package com.hotel.hotelbookingsystem.inventory;

import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InventoryLock {
    private static final Logger log = LoggerFactory.getLogger(InventoryLock.class);
    private final RedissonClient redisson;
    private final String prefix;
    private final long waitMs;

    public InventoryLock(@Lazy RedissonClient redisson,
            @Value("${hotel.inventory.lock.prefix}") String prefix,
            @Value("${hotel.inventory.lock.wait-ms}") long waitMs) {
        if (prefix == null || prefix.isBlank() || waitMs < 0) {
            throw new IllegalArgumentException("Invalid inventory lock configuration");
        }
        this.redisson = redisson;
        this.prefix = prefix;
        this.waitMs = waitMs;
    }

    public void execute(Long roomTypeId, Runnable transaction) {
        RLock lock;
        boolean acquired;
        try {
            lock = redisson.getLock(prefix + roomTypeId);
            // Omit leaseTime so Redisson renews the owner's lease with its watchdog.
            acquired = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("库存请求已中断，请稍后重试", exception);
        } catch (RuntimeException exception) {
            log.warn("Inventory lock unavailable for room type {}", roomTypeId, exception);
            throw unavailable("库存锁服务暂不可用，请稍后重试", exception);
        }
        if (!acquired) throw unavailable("该房型预订繁忙，请稍后重试", null);

        Throwable failure = null;
        try {
            transaction.run();
        } catch (RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                // Redisson checks ownership atomically. Never force-unlock another owner.
                lock.unlock();
            } catch (RuntimeException exception) {
                log.warn("Inventory lock release failed for room type {}", roomTypeId, exception);
                if (failure != null) failure.addSuppressed(exception);
                else throw unavailable("库存锁状态异常，请稍后查询订单或使用原请求编号重试", exception);
            }
        }
    }

    private ResponseStatusException unavailable(String message, Throwable cause) {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, cause);
    }
}
