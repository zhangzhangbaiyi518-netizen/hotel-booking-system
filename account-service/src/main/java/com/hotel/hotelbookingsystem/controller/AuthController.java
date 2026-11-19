package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.dto.LoginRequest;
import com.hotel.hotelbookingsystem.entity.User;
import com.hotel.hotelbookingsystem.service.AccountService;
import com.hotel.hotelbookingsystem.service.JwtService;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hotel")
public class AuthController {
    private final AccountService accounts;
    private final JwtService jwtService;

    public AuthController(AccountService accounts, JwtService jwtService) {
        this.accounts = accounts;
        this.jwtService = jwtService;
    }

    @PostMapping("/auth/register")
    public User register(@RequestBody User user) {
        return accounts.register(user);
    }
    @PostMapping("/auth/login")
    public Map<String, Object> login(
            @RequestBody LoginRequest request) {

        User user = accounts.login(
                request.getUsername(), request.getPassword());

        return Map.of(
                "accessToken", jwtService.createToken(user.getId()),
                "tokenType", "Bearer",
                "expiresIn", 3600,
                "id", user.getId(),
                "username", user.getUsername(),
                "message", "登录成功"
        );
    }
    @GetMapping("/auth/me")
    public Map<String, Object> currentUser(
            @AuthenticationPrincipal Jwt jwt) {
        return Map.of("id", Long.valueOf(jwt.getSubject()));
    }
    @PostMapping("/auth/logout")
    public Map<String, String> logout() {
        return Map.of("message", "请在客户端删除令牌；令牌在过期前仍然有效");
    }
}
