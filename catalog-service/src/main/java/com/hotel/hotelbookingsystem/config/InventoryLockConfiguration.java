package com.hotel.hotelbookingsystem.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class InventoryLockConfiguration {
    // Lazy initialization lets catalog reads retain their database fallback if Redis is down.
    @Bean(destroyMethod = "shutdown")
    @Lazy
    public RedissonClient inventoryRedisson(RedisProperties redis,
            @Value("${hotel.inventory.lock.watchdog-timeout-ms}") long watchdogTimeout) {
        if (watchdogTimeout < 1000) throw new IllegalArgumentException("Inventory lock watchdog must be at least 1000 ms");
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.setThreads(2);
        config.setNettyThreads(2);
        config.setLockWatchdogTimeout(watchdogTimeout);
        var server = config.useSingleServer()
                .setAddress((redis.isSsl() ? "rediss://" : "redis://") + redis.getHost() + ":" + redis.getPort())
                .setDatabase(redis.getDatabase())
                .setConnectTimeout(1000)
                .setTimeout(1000)
                .setRetryAttempts(0)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(4);
        if (redis.getUsername() != null && !redis.getUsername().isBlank()) server.setUsername(redis.getUsername());
        if (redis.getPassword() != null && !redis.getPassword().isBlank()) server.setPassword(redis.getPassword());
        return Redisson.create(config);
    }
}
