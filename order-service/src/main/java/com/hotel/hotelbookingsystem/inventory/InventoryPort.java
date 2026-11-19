package com.hotel.hotelbookingsystem.inventory;

public interface InventoryPort {
    RoomInventory inventory(Long roomTypeId);
    void reserve(Long userId, String orderId, Long roomTypeId);
    void release(Long userId, String orderId);
    void importHeld(Long userId, String orderId, Long roomTypeId, Integer total);
}
