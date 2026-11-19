package com.hotel.hotelbookingsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class OrderSpendingClientFallbackFactory implements FallbackFactory<OrderSpendingClient> {
    private static final Logger log = LoggerFactory.getLogger(OrderSpendingClientFallbackFactory.class);

    @Override
    public OrderSpendingClient create(Throwable cause) {
        log.warn("Order spending call degraded ({})", cause.getClass().getSimpleName());
        return authorization -> null;
    }
}
