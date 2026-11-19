package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.service.CultivationService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/hotel")
public class CultivationController {
    private final CultivationService service;
    public CultivationController(CultivationService service) { this.service=service; }
    public record Recharge(String requestId,BigDecimal amount) {}
    public record Action(String requestId,Integer generation,String code) {}
    @GetMapping("/wallet") public CultivationService.Wallet wallet(@AuthenticationPrincipal Jwt jwt) { return service.wallet(Long.valueOf(jwt.getSubject())); }
    @PostMapping("/wallet/recharge") public CultivationService.Wallet recharge(@AuthenticationPrincipal Jwt jwt,@RequestBody Recharge req) {
        return service.recharge(Long.valueOf(jwt.getSubject()),req.requestId(),req.amount());
    }
    @GetMapping("/pet/training") public CultivationService.Training get(@AuthenticationPrincipal Jwt jwt) { return service.get(Long.valueOf(jwt.getSubject())); }
    @PostMapping("/pet/training/start") public CultivationService.Training start(@AuthenticationPrincipal Jwt jwt,@RequestBody Action req) { return service.start(Long.valueOf(jwt.getSubject()),req.generation()); }
    @PostMapping("/pet/training/buy") public CultivationService.Training buy(@AuthenticationPrincipal Jwt jwt,@RequestBody Action req) { return service.buy(Long.valueOf(jwt.getSubject()),req.requestId(),req.generation(),req.code()); }
    @PostMapping("/pet/training/reward") public CultivationService.Reward reward(@AuthenticationPrincipal Jwt jwt,@RequestBody Action req) { return service.reward(Long.valueOf(jwt.getSubject()),req.generation()); }
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> error(ResponseStatusException ex) { return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason())); }
}
