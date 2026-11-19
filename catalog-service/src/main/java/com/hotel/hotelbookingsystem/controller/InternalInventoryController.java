package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import com.hotel.hotelbookingsystem.service.InventoryService;
import io.seata.core.context.RootContext;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/inventory")
public class InternalInventoryController {
    private final InventoryService inventory;

    public InternalInventoryController(InventoryService inventory) { this.inventory = inventory; }
    public record Reservation(String orderId, Long roomTypeId, Integer total) {}

    private void requireService(Jwt jwt, boolean write) {
        if (!"inventory".equals(jwt.getClaimAsString("service"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅允许库存服务调用");
        }
        if (write && RootContext.getXID() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "库存写入需要全局事务");
        }
    }

    @GetMapping("/{roomTypeId}")
    public RoomInventory inventory(@AuthenticationPrincipal Jwt jwt, @PathVariable Long roomTypeId) {
        requireService(jwt, false);
        return inventory.inventory(roomTypeId);
    }

    @PostMapping("/reserve")
    public void reserve(@AuthenticationPrincipal Jwt jwt, @RequestBody Reservation request) {
        requireService(jwt, true);
        inventory.reserve(Long.valueOf(jwt.getSubject()), request.orderId(), request.roomTypeId());
    }

    @PostMapping("/release/{orderId}")
    public void release(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        requireService(jwt, true);
        inventory.release(Long.valueOf(jwt.getSubject()), orderId);
    }

    @PostMapping("/import")
    public void importHeld(@AuthenticationPrincipal Jwt jwt, @RequestBody Reservation request) {
        requireService(jwt, true);
        inventory.importHeld(Long.valueOf(jwt.getSubject()), request.orderId(), request.roomTypeId(), request.total());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
    }
}
