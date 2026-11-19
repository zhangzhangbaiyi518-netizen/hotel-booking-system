package com.hotel.hotelbookingsystem.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class CatalogClientFallbackFactory implements FallbackFactory<CatalogClient> {
    private static final Logger log = LoggerFactory.getLogger(CatalogClientFallbackFactory.class);

    @Override
    public CatalogClient create(Throwable cause) {
        log.warn("Catalog call degraded ({})", cause.getClass().getSimpleName());
        return (authorization, id) -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
