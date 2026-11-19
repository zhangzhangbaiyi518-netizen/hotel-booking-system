package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.mapper.HotelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class InternalCatalogController {
    private final HotelMapper catalog;

    InternalCatalogController(HotelMapper catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/internal/catalog/rooms/{id}")
    RoomSnapshot room(@PathVariable Long id) {
        RoomSnapshot room = catalog.selectRoomSnapshot(id);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "房型或酒店不存在");
        }
        return room;
    }
}
