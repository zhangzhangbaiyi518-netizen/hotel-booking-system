package com.hotel.hotelbookingsystem.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ProfileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Profile(Long id, String username, String nickname, String phone,
                          String role, Integer status, LocalDateTime createTime) {}

    public Profile get(Long userId) {
        var users = jdbc.query("SELECT id, username, nickname, phone, role, status, create_time FROM `user` WHERE id = ?",
                (rs, row) -> new Profile(rs.getLong("id"), rs.getString("username"), rs.getString("nickname"),
                        rs.getString("phone"), rs.getString("role"), rs.getInt("status"),
                        rs.getTimestamp("create_time") == null ? null : rs.getTimestamp("create_time").toLocalDateTime()), userId);
        if (users.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
        if (!Integer.valueOf(1).equals(users.get(0).status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已禁用");
        }
        return users.get(0);
    }

    @Transactional
    public Profile update(Long userId, String nickname, String phone) {
        get(userId);
        nickname = nickname == null ? "" : nickname.trim();
        phone = phone == null ? "" : phone.trim();
        if (nickname.length() > 50 || phone.length() > 20 || (!phone.isEmpty() && !phone.matches("[+0-9() -]{3,20}"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "昵称最多50字符，请填写有效联系电话（最多20字符）");
        }
        jdbc.update("UPDATE `user` SET nickname = ?, phone = ?, update_time = CURRENT_TIMESTAMP WHERE id = ?",
                nickname, phone, userId);
        return get(userId);
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        get(userId);
        if (oldPassword == null || oldPassword.isBlank() || newPassword == null || newPassword.isBlank()
                || newPassword.length() < 8 || newPassword.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少8字符且不超过72字节，请填写原密码");
        }
        String oldHash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = ?", String.class, userId);
        if (oldHash == null || !encoder.matches(oldPassword, oldHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原密码不正确");
        }
        if (encoder.matches(newPassword, oldHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码不能与原密码相同");
        }
        int changed = jdbc.update("UPDATE `user` SET password_hash = ?, update_time = CURRENT_TIMESTAMP " +
                "WHERE id = ? AND password_hash = ?", encoder.encode(newPassword), userId, oldHash);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "密码已变更，请重新登录");
    }
}
