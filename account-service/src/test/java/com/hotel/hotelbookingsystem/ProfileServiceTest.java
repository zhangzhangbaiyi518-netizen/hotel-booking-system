package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.service.ProfileService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class ProfileServiceTest {
    private JdbcTemplate jdbc;
    private ProfileService profiles;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE `user` (id BIGINT PRIMARY KEY, username VARCHAR(50), password_hash VARCHAR(255), " +
                "nickname VARCHAR(50), phone VARCHAR(20), role VARCHAR(20), status INT, " +
                "create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO `user` (id, username, password_hash, role, status) VALUES (1, 'alice', ?, 'USER', 1)", encoder.encode("Hotel123456"));
        jdbc.update("INSERT INTO `user` (id, username, password_hash, role, status) VALUES (2, 'bob', ?, 'USER', 1)", encoder.encode("Other123456"));
        profiles = new ProfileService(jdbc);
    }

    @Test
    void profileUpdatesOnlyAllowedFieldsOfSelectedUser() {
        var updated = profiles.update(1L, "  小张  ", "+86 13800138000");
        assertEquals("小张", updated.nickname());
        assertEquals("+86 13800138000", updated.phone());
        assertEquals("alice", updated.username());
        assertEquals("USER", updated.role());
        assertNull(profiles.get(2L).nickname());
        assertNotNull(updated.createTime());
        assertThrows(ResponseStatusException.class, () -> profiles.update(1L, "字".repeat(51), ""));
        assertThrows(ResponseStatusException.class, () -> profiles.update(1L, "小张", "invalid"));
    }

    @Test
    void passwordChangeVerifiesOldPasswordAndStoresOnlyHash() {
        assertThrows(ResponseStatusException.class, () -> profiles.changePassword(1L, "wrong", "NewHotel123"));
        assertThrows(ResponseStatusException.class, () -> profiles.changePassword(1L, "Hotel123456", "short"));
        assertThrows(ResponseStatusException.class, () -> profiles.changePassword(1L, "Hotel123456", "Hotel123456"));
        assertThrows(ResponseStatusException.class, () -> profiles.changePassword(1L, "Hotel123456", "密".repeat(25)));
        profiles.changePassword(1L, "Hotel123456", "NewHotel123");
        String hash = jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = 1", String.class);
        assertNotEquals("NewHotel123", hash);
        assertTrue(encoder.matches("NewHotel123", hash));
        assertFalse(encoder.matches("Hotel123456", hash));
        assertTrue(encoder.matches("Other123456", jdbc.queryForObject("SELECT password_hash FROM `user` WHERE id = 2", String.class)));
    }

    @Test
    void missingOrDisabledUsersCannotEditProfileOrPassword() {
        assertThrows(ResponseStatusException.class, () -> profiles.get(999L));
        jdbc.update("UPDATE `user` SET status = 0 WHERE id = 1");
        assertThrows(ResponseStatusException.class, () -> profiles.update(1L, "新昵称", ""));
        assertThrows(ResponseStatusException.class, () -> profiles.changePassword(1L, "Hotel123456", "NewHotel123"));
    }
}
