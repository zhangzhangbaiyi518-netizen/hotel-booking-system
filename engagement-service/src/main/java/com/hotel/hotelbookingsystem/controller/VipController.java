package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.client.OrderSpendingClient;
import com.hotel.hotelbookingsystem.client.PetSpendingClient;
import com.hotel.hotelbookingsystem.service.VipService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/hotel/vip")
public class VipController {
    private final VipService vip;
    private final OrderSpendingClient orders;
    private final PetSpendingClient pets;

    public VipController(VipService vip, OrderSpendingClient orders, PetSpendingClient pets) {
        this.vip = vip;
        this.orders = orders;
        this.pets = pets;
    }

    @GetMapping
    public VipService.Status get(@AuthenticationPrincipal Jwt jwt) {
        return vip.get(Long.valueOf(jwt.getSubject()), totalSpending(jwt));
    }

    @PostMapping("/claim")
    public VipService.Status claim(@AuthenticationPrincipal Jwt jwt) {
        return vip.claim(Long.valueOf(jwt.getSubject()), totalSpending(jwt));
    }

    private BigDecimal totalSpending(Jwt jwt) {
        String authorization = "Bearer " + jwt.getTokenValue();
        BigDecimal orderSpending = orders.spending(authorization);
        BigDecimal petSpending = pets.spending(authorization);
        if (orderSpending == null || petSpending == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "会员消费统计暂不可用，请稍后重试");
        }
        return orderSpending.add(petSpending);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", exception.getReason()));
    }
}
