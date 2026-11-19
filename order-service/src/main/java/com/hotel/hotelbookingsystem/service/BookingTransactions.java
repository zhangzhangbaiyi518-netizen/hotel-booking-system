package com.hotel.hotelbookingsystem.service;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import io.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

@Service
public class BookingTransactions {
    private final StayOrderService orders;

    public BookingTransactions(StayOrderService orders) { this.orders = orders; }

    @GlobalTransactional(name = "hotel-create-order", rollbackFor = Exception.class, timeoutMills = 30000)
    public StayOrderService.StayOrder create(Long userId, RoomSnapshot room, String requestId) {
        return orders.create(userId, room, requestId);
    }

    @GlobalTransactional(name = "hotel-cancel-order", rollbackFor = Exception.class)
    public StayOrderService.StayOrder cancel(Long userId, String id) { return orders.cancel(userId, id); }

    @GlobalTransactional(name = "hotel-refund-order", rollbackFor = Exception.class)
    public StayOrderService.StayOrder refund(Long userId, String id, String reason) { return orders.refund(userId, id, reason); }

    @GlobalTransactional(name = "hotel-inventory", rollbackFor = Exception.class)
    public RoomInventory inventory(Long roomTypeId) { return orders.inventory(roomTypeId); }

    @GlobalTransactional(name = "hotel-release-completed", rollbackFor = Exception.class, timeoutMills = 60000)
    public void releaseCompletedStays() { orders.releaseCompletedStays(); }

    @GlobalTransactional(name = "hotel-import-legacy-stock", rollbackFor = Exception.class, timeoutMills = 120000)
    public void migrateLegacyInventory() { orders.migrateLegacyInventory(); }

    @GlobalTransactional(name = "hotel-timeout-cancel", rollbackFor = Exception.class, timeoutMills = 30000)
    public java.time.LocalDateTime cancelExpired(String id) { return orders.cancelExpired(id); }
}
