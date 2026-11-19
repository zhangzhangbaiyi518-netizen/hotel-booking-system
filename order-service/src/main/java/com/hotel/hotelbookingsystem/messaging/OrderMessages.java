package com.hotel.hotelbookingsystem.messaging;

import java.time.LocalDateTime;
import com.hotel.hotelbookingsystem.service.StayOrderService.StayOrder;

public final class OrderMessages {
    private OrderMessages() {}

    public record StayInfo(String hotelName, String hotelAddress, String hotelPhone,
            String roomName, Integer capacity, int nights, LocalDateTime checkInTime,
            LocalDateTime checkOutTime, String roomNumber, String checkInRule) {}
    public record Created(String eventId, String orderId, String phone, boolean virtualPhone,
            String status, LocalDateTime createdAt, LocalDateTime paymentDeadline, StayInfo stay) {}
    public record Timeout(String eventId, String orderId) {}

    public static Created created(StayOrder order, Long userId) {
        String phone = "1990000" + String.format(java.util.Locale.ROOT, "%04d", Math.floorMod(userId, 10000));
        return new Created("created:" + order.id(), order.id(), phone, true, order.status(),
                order.createTime(), order.paymentDeadline(), new StayInfo(order.hotelName(), order.hotelAddress(),
                order.hotelPhone(), order.roomName(), order.capacity(), 1, order.checkInTime(), order.checkOutTime(),
                order.roomNumber(), "付款成功后1小时入住，入住后24小时退房；具体时间及房号在付款后确定"));
    }
}
