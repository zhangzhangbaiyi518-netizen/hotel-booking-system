package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.service.SpiritPetService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/hotel/pet")
public class SpiritPetController {
    private final SpiritPetService pets;
    public SpiritPetController(SpiritPetService pets) { this.pets = pets; }
    public record SpinRequest(String requestId, Integer generation) {}

    @GetMapping
    public SpiritPetService.Pet get(@AuthenticationPrincipal Jwt jwt) {
        return pets.get(Long.valueOf(jwt.getSubject()));
    }

    @PostMapping("/spin")
    public SpiritPetService.SpinResult spin(@AuthenticationPrincipal Jwt jwt, @RequestBody SpinRequest request) {
        if (request.generation() == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "请刷新页面后重试");
        return pets.spin(Long.valueOf(jwt.getSubject()), request.requestId(), request.generation());
    }

    @PostMapping("/restart")
    public SpiritPetService.Pet restart(@AuthenticationPrincipal Jwt jwt, @RequestBody SpinRequest request) {
        return pets.restart(Long.valueOf(jwt.getSubject()), request.requestId(), request.generation());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
    }
}
