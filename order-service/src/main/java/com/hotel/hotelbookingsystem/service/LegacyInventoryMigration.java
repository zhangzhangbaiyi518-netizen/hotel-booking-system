package com.hotel.hotelbookingsystem.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(10)
public class LegacyInventoryMigration implements ApplicationRunner {
    private final BookingTransactions bookings;

    public LegacyInventoryMigration(BookingTransactions bookings) { this.bookings = bookings; }

    @Override
    public void run(ApplicationArguments args) { bookings.migrateLegacyInventory(); }
}
