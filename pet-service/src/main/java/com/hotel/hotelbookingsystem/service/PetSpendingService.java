package com.hotel.hotelbookingsystem.service;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PetSpendingService {
    private final JdbcTemplate jdbc;

    public PetSpendingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public BigDecimal spending(Long userId) {
        BigDecimal draws = jdbc.queryForObject(
                "SELECT COALESCE(SUM(simulated_cost), 0) FROM spirit_pet_draw WHERE user_id = ?",
                BigDecimal.class, userId);
        BigDecimal supplements = jdbc.queryForObject(
                "SELECT COALESCE(-SUM(amount), 0) FROM account_transaction WHERE user_id = ? AND type = 'PET_SUPPLEMENT'",
                BigDecimal.class, userId);
        return draws.add(supplements);
    }
}
