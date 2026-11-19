package com.hotel.hotelbookingsystem.client;

import com.hotel.hotelbookingsystem.http.InternalFeignConfiguration;
import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "catalog-service",
        configuration = InternalFeignConfiguration.class,
        fallbackFactory = CatalogClientFallbackFactory.class, dismiss404 = true)
public interface CatalogClient {
    @GetMapping("/internal/catalog/rooms/{id}")
    ResponseEntity<RoomSnapshot> room(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable("id") Long id);
}
