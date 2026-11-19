package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.service.StayOrderService;
import java.math.BigDecimal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InternalOrderController {
    private final StayOrderService orders;

    InternalOrderController(StayOrderService orders) {
        this.orders = orders;
    }

    @GetMapping("/internal/orders/spending")
    BigDecimal spending(@AuthenticationPrincipal Jwt jwt) {
        return orders.paidSpending(Long.valueOf(jwt.getSubject()));
    }
}
