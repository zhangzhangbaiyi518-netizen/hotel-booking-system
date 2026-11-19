package com.hotel.hotelbookingsystem.search;

import com.hotel.hotelbookingsystem.entity.Hotel;
import java.util.List;

public record HotelSearchPage(List<Hotel> items, long total, int page, int size, String source) {}
