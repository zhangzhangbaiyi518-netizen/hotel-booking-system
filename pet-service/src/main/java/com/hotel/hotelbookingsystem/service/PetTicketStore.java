package com.hotel.hotelbookingsystem.service;

import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** The VIP issuer and pet draws share this wallet to keep ticket updates atomic. */
public final class PetTicketStore {
    private PetTicketStore() {}

    public static void initialize(JdbcTemplate jdbc) {
        new ResourceDatabasePopulator(new ClassPathResource("db/pet-tickets.sql"))
                .execute(Objects.requireNonNull(jdbc.getDataSource()));
    }

    public static void ensureWallet(JdbcTemplate jdbc, Long userId) {
        jdbc.update("INSERT INTO vip_wallet (user_id) VALUES (?) ON DUPLICATE KEY UPDATE user_id = user_id", userId);
    }
}
