package com.hotel.hotelbookingsystem.service;



import com.hotel.hotelbookingsystem.entity.Hotel;
import com.hotel.hotelbookingsystem.entity.Room;

import java.util.List;

public interface HotelService {

    List<Hotel> getAllHotels();

    Hotel getHotelById(Long id);

    Hotel searchHotels(String phone);

    Room getRoomById(Long id);

    List<Room> getRoomsByHotelId(Long hotelId);
}
