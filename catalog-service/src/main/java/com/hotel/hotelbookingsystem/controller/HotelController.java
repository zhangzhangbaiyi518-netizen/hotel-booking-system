package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.entity.Hotel;
import com.hotel.hotelbookingsystem.entity.Room;
import com.hotel.hotelbookingsystem.service.HotelService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/hotel")
public class HotelController {

    private final HotelService hotelService;

    public HotelController(HotelService hotelService) {
        this.hotelService = hotelService;
    }

    @GetMapping("/searchbyphone")
    public Hotel search(@RequestParam String phone) {
        return hotelService.searchHotels(phone);
    }

    @GetMapping("/searchbyid")
    public Hotel searchById(@RequestParam Long id) {
        return hotelService.getHotelById(id);
    }
    @GetMapping("/searchroombyid")
    public Room searchRoomById(@RequestParam Long id) {
        return hotelService.getRoomById(id);
    }
    @GetMapping("/rooms")
    public List<Room> searchRoomsByHotelId(@RequestParam("hotelId") Long hotelId) {
        return hotelService.getRoomsByHotelId(hotelId);
    }
    @GetMapping("/searchall")
    public List<Hotel> searchAll() {
        return hotelService.getAllHotels();
    }
}
