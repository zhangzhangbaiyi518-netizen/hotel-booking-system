package com.hotel.hotelbookingsystem.service.impl;
import com.hotel.hotelbookingsystem.cache.CatalogCache;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.hotel.hotelbookingsystem.entity.Hotel;
import com.hotel.hotelbookingsystem.entity.Room;
import com.hotel.hotelbookingsystem.mapper.HotelMapper;
import com.hotel.hotelbookingsystem.service.HotelService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HotelServiceimpl implements HotelService {
    private final HotelMapper hotelMapper;

    private final CatalogCache cache;

    public HotelServiceimpl(HotelMapper hotelMapper, CatalogCache cache) {
        this.hotelMapper = hotelMapper;
        this.cache = cache;
    }

    @Override
    public List<Hotel> getAllHotels() {
        return cache.list("hotels", Hotel.class, hotelMapper::selectAll);
    }

    @Override
    public Hotel getHotelById(Long id) {
        return cache.get("hotel:" + id, Hotel.class, () -> hotelMapper.selectById(id));
    }

    @Override
    public Hotel searchHotels(String phone) {
        return cache.get("phone:" + phone, Hotel.class, () -> hotelMapper.selectByPhone(phone));
    }

    @Override
    public Room getRoomById(Long id) {
        return cache.get("room:" + id, Room.class, () -> hotelMapper.selectRoomById(id));
    }

    @Override
    public List<Room> getRoomsByHotelId(Long hotelId) {
        if (hotelId == null || hotelId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "酒店 ID 必须为正整数");
        }
        if (getHotelById(hotelId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "酒店不存在");
        }
        return cache.list("rooms:" + hotelId, Room.class, () -> hotelMapper.selectRoomsByHotelId(hotelId));
    }
}
