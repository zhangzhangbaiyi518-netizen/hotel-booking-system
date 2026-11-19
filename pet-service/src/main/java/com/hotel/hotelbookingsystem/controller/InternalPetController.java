package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.service.PetSpendingService;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InternalPetController {
    private final PetSpendingService pets;

    InternalPetController(PetSpendingService pets) {
        this.pets = pets;
    }

    @GetMapping("/internal/pet/spending")
    BigDecimal spending(@AuthenticationPrincipal Jwt jwt) {
        return pets.spending(Long.valueOf(jwt.getSubject()));
    }
}
