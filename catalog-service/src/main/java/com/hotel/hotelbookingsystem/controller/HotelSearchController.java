package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.search.HotelSearchPage;
import com.hotel.hotelbookingsystem.search.HotelSearchService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/hotel/search")
public class HotelSearchController {
    private final HotelSearchService search;
    public HotelSearchController(HotelSearchService search) { this.search = search; }
    @GetMapping
    public HotelSearchPage search(@RequestParam(defaultValue = "") String keyword,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return search.search(keyword, page, size);
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
    }
}
