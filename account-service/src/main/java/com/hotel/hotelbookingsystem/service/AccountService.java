package com.hotel.hotelbookingsystem.service;

import com.hotel.hotelbookingsystem.entity.User;
import com.hotel.hotelbookingsystem.mapper.AccountMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService {
    private final AccountMapper accounts;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AccountService(AccountMapper accounts) {
        this.accounts = accounts;
    }

    public User register(User user) {
        if (user.getUsername() == null || user.getUsername().isBlank()
                || user.getPasswordHash() == null
                || user.getPasswordHash().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "用户名和密码不能为空");
        }

        String username = user.getUsername().trim();
        String password = user.getPasswordHash();

        if (username.length() > 50 || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "用户名最多50字符，密码至少8字符且不超过72字节");
        }

        if (accounts.selectByUsername(username) != null) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "用户名已存在");
        }

        // 创建新对象，避免使用客户端传入的角色和状态
        User newUser = new User();
        newUser.setUsername(username);
        newUser.setPasswordHash(
                encoder.encode(password));
        newUser.setRole("USER");
        newUser.setStatus(1);

        try {
            accounts.insertUser(newUser);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "用户名已存在");
        }

        // 返回资料时去掉密码哈希
        newUser.setPasswordHash(null);
        return newUser;
    }
    public User login(String username, String password) {
        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "用户名和密码不能为空");
        }

        User user = accounts.selectByUsername(username.trim());

        if (user == null || user.getPasswordHash() == null
                || !encoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }

        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "账号已禁用");
        }

        return user;
    }
}
