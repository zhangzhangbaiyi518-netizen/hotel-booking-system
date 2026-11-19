package com.hotel.hotelbookingsystem.client;

import com.hotel.hotelbookingsystem.http.InternalFeignConfiguration;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@FeignClient(name = "catalog-service", contextId = "inventory-client", configuration = {InternalFeignConfiguration.class, InventoryFeignConfiguration.class})
public interface InventoryClient {
    record Reservation(String orderId, Long roomTypeId, Integer total) {}

    @GetMapping("/internal/inventory/{roomTypeId}")
    RoomInventory inventory(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable("roomTypeId") Long roomTypeId);

    @PostMapping("/internal/inventory/reserve")
    void reserve(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @RequestBody Reservation reservation);

    @PostMapping("/internal/inventory/release/{orderId}")
    void release(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @PathVariable("orderId") String orderId);

    @PostMapping("/internal/inventory/import")
    void importHeld(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization, @RequestBody Reservation reservation);
}
