package com.hotel.hotelbookingsystem.http;

import java.math.BigDecimal;

public record RoomSnapshot(Long roomTypeId, Long hotelId, String hotelName, String hotelAddress,
                           String hotelPhone, String roomName, Integer capacity, String description,
                           BigDecimal price) {}
