package com.hotel.hotelbookingsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class PetSpendingClientFallbackFactory implements FallbackFactory<PetSpendingClient> {
    private static final Logger log = LoggerFactory.getLogger(PetSpendingClientFallbackFactory.class);

    @Override
    public PetSpendingClient create(Throwable cause) {
        log.warn("Pet spending call degraded ({})", cause.getClass().getSimpleName());
        return authorization -> null;
    }
}
