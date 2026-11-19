package com.hotel.hotelbookingsystem.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryMaintenance {
    private final BookingTransactions orders;
    private final com.hotel.hotelbookingsystem.config.BookingReadiness readiness;

    public InventoryMaintenance(BookingTransactions orders, com.hotel.hotelbookingsystem.config.BookingReadiness readiness) {
        this.orders = orders;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${hotel.inventory.release-interval-ms:30000}", initialDelay = 30000)
    public void releaseCompletedStays() {
        if (readiness.isReady()) orders.releaseCompletedStays();
    }
}
