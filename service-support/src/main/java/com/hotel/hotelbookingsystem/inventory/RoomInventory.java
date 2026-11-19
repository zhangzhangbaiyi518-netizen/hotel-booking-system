package com.hotel.hotelbookingsystem.inventory;

public record RoomInventory(Long roomTypeId, int total, int reserved, int available) {}
