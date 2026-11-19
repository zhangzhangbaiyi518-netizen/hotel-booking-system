package com.hotel.hotelbookingsystem.controller;

import com.hotel.hotelbookingsystem.service.ProfileService;
import com.hotel.hotelbookingsystem.service.ProfileService.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/hotel/profile")
public class ProfileController {
    private final ProfileService profiles;
    public ProfileController(ProfileService profiles) { this.profiles = profiles; }
    public record UpdateProfile(String nickname, String phone) {}
    public record ChangePassword(String oldPassword, String newPassword) {}

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode())
                .body(Map.of("message", exception.getReason() == null ? "操作失败" : exception.getReason()));
    }

    @GetMapping
    public Profile get(@AuthenticationPrincipal Jwt jwt) {
        return profiles.get(Long.valueOf(jwt.getSubject()));
    }

    @PutMapping
    public Profile update(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateProfile request) {
        return profiles.update(Long.valueOf(jwt.getSubject()), request.nickname(), request.phone());
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal Jwt jwt, @RequestBody ChangePassword request) {
        profiles.changePassword(Long.valueOf(jwt.getSubject()), request.oldPassword(), request.newPassword());
    }
}
