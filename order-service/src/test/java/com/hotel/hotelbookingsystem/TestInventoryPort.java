package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.inventory.InventoryLedger;
import com.hotel.hotelbookingsystem.inventory.InventoryPort;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import org.springframework.jdbc.core.JdbcTemplate;

class TestInventoryPort implements InventoryPort {
    private final InventoryLedger ledger;

    TestInventoryPort(JdbcTemplate jdbc, int stock) {
        ledger = new InventoryLedger(jdbc, stock);
        ledger.initialize();
    }

    public RoomInventory inventory(Long roomTypeId) { return ledger.inventory(roomTypeId); }
    public void reserve(Long userId, String orderId, Long roomTypeId) { ledger.reserve(userId, orderId, roomTypeId); }
    public void release(Long userId, String orderId) { ledger.release(userId, orderId); }
    public void importHeld(Long userId, String orderId, Long roomTypeId, Integer total) {
        ledger.importReservation(userId, orderId, roomTypeId, total);
    }
}
