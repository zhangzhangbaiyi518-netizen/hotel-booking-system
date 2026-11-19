package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StayOrderServiceTest {
    private JdbcTemplate jdbc;
    private StayOrderService service;
    private final RoomSnapshot room = new RoomSnapshot(4L, 2L, "测试酒店", "上海", "123456",
            "标准大床房", 2, "一张大床", new BigDecimal("328.00"));

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        service = new StayOrderService(jdbc, new TestInventoryPort(jdbc, 10));
        service.run(null);
    }

    @Test
    void purchaseAndPaymentUseSnapshotsAndPersistOnce() {
        var order = service.create(1L, room);
        assertEquals(new BigDecimal("328.00"), order.amount());
        assertEquals("PENDING_PAYMENT", order.status());
        assertTrue(service.listAwaitingCheckIn(1L).isEmpty());
        var paid = service.pay(1L, order.id());
        assertEquals("AWAITING_CHECK_IN", paid.status());
        assertEquals(new BigDecimal("328.00"), paid.amount());
        assertEquals("标准大床房", paid.roomName());
        assertNotNull(paid.paidTime());
        assertEquals(paid.paidTime().plusHours(1), paid.checkInTime());
        assertEquals(paid.checkInTime().plusDays(1), paid.checkOutTime());
        assertTrue(paid.roomNumber().matches("[1-9][0-9]{2}"));
        assertEquals(paid.paidTime(), service.pay(1L, order.id()).paidTime());
        assertEquals(paid.roomNumber(), service.pay(1L, order.id()).roomNumber());
        assertEquals(1, service.listAwaitingCheckIn(1L).size());
        // A new service instance still retrieves the persisted order.
        assertEquals(order.id(), new StayOrderService(jdbc, new TestInventoryPort(jdbc, 10)).listAwaitingCheckIn(1L).get(0).id());
    }

    @Test
    void anotherUserCannotReadPayOrListTheOrder() {
        var order = service.create(1L, room);
        assertEquals(404, assertThrows(ResponseStatusException.class,
                () -> service.get(2L, order.id())).getStatusCode().value());
        assertThrows(ResponseStatusException.class, () -> service.pay(2L, order.id()));
        assertEquals("PENDING_PAYMENT", service.get(1L, order.id()).status());
        service.pay(1L, order.id());
        assertTrue(service.listAwaitingCheckIn(2L).isEmpty());
    }

    @Test
    void unpricedRoomsCannotBePurchased() {
        var unavailable = new RoomSnapshot(4L, 2L, "测试酒店", "上海", "123456",
                "标准大床房", 2, "一张大床", BigDecimal.ZERO);
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.create(1L, unavailable)).getStatusCode().value());
        assertTrue(service.listAll(1L).isEmpty());
    }

    @Test
    void spendingCountsPaidOrdersUntilRefunded() {
        var order = service.create(1L, room);
        assertEquals(0, service.paidSpending(1L).signum());
        service.pay(1L, order.id());
        assertEquals(room.price(), service.paidSpending(1L));
        assertEquals(0, service.paidSpending(2L).signum());
        service.requestRefund(1L, order.id());
        assertEquals(room.price(), service.paidSpending(1L));
        service.refund(1L, order.id(), "行程有变");
        service.deleteRefund(1L, order.id());
        assertEquals(0, service.paidSpending(1L).signum());
    }

    @Test
    void endedStaysAreHiddenButOrderHistoryRemains() {
        var order = service.create(1L, room);
        service.pay(1L, order.id());
        jdbc.update("UPDATE stay_order SET check_out_time = ? WHERE id = ?",
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")).minusSeconds(1), order.id());
        assertTrue(service.listAwaitingCheckIn(1L).isEmpty());
        assertEquals(order.id(), service.get(1L, order.id()).id());
    }

    @Test
    void existingPaidOrdersGetScheduleOnStartupWithoutBeingReassigned() {
        var order = service.pay(1L, service.create(1L, room).id());
        jdbc.execute("DROP INDEX idx_stay_order_inventory");
        jdbc.execute("ALTER TABLE stay_order DROP COLUMN check_in_time");
        jdbc.execute("ALTER TABLE stay_order DROP COLUMN check_out_time");
        jdbc.execute("ALTER TABLE stay_order DROP COLUMN room_number");
        service.run(null);
        var upgraded = service.get(1L, order.id());
        assertEquals(upgraded.paidTime().plusHours(1), upgraded.checkInTime());
        assertEquals(upgraded.checkInTime().plusDays(1), upgraded.checkOutTime());
        assertTrue(upgraded.roomNumber().matches("[1-9][0-9]{2}"));
        service.run(null);
        assertEquals(upgraded.roomNumber(), service.get(1L, order.id()).roomNumber());
    }

    @Test
    void refundMovesOrderOutOfStaysAndRecordsReasonOnlyOnce() {
        var paid = service.pay(1L, service.create(1L, room).id());
        assertThrows(ResponseStatusException.class, () -> service.refund(1L, paid.id(), "行程有变"));
        var requested = service.requestRefund(1L, paid.id());
        assertEquals("REFUND_REQUESTED", requested.status());
        assertNotNull(requested.refundRequestTime());
        assertTrue(service.listAwaitingCheckIn(1L).isEmpty());
        assertEquals(1, service.listRefunds(1L).size());
        assertEquals(requested.refundRequestTime(), service.requestRefund(1L, paid.id()).refundRequestTime());
        assertThrows(ResponseStatusException.class, () -> service.refund(1L, paid.id(), ""));
        assertThrows(ResponseStatusException.class, () -> service.refund(1L, paid.id(), "非法原因"));
        var refunded = service.refund(1L, paid.id(), "行程有变");
        assertEquals("REFUNDED", refunded.status());
        assertEquals("行程有变", refunded.refundReason());
        assertNotNull(refunded.refundTime());
        assertEquals(paid.amount(), refunded.amount());
        var repeated = service.refund(1L, paid.id(), "价格原因");
        assertEquals(refunded.refundTime(), repeated.refundTime());
        assertEquals(refunded.refundReason(), repeated.refundReason());
        assertEquals(1, service.listRefunds(1L).size());
        assertThrows(ResponseStatusException.class, () -> service.pay(1L, paid.id()));
    }

    @Test
    void refundRejectsOtherUsersUnpaidOrdersAndExpiredStays() {
        var pending = service.create(1L, room);
        assertThrows(ResponseStatusException.class, () -> service.requestRefund(1L, pending.id()));
        var paid = service.pay(1L, pending.id());
        assertThrows(ResponseStatusException.class, () -> service.requestRefund(2L, paid.id()));
        assertEquals("AWAITING_CHECK_IN", service.get(1L, paid.id()).status());
        service.requestRefund(1L, paid.id());
        assertTrue(service.listRefunds(2L).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.refund(2L, paid.id(), "行程有变"));
        assertEquals("REFUND_REQUESTED", service.get(1L, paid.id()).status());
        var expired = service.pay(1L, service.create(1L, room).id());
        jdbc.update("UPDATE stay_order SET check_out_time = ? WHERE id = ?",
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")).minusSeconds(1), expired.id());
        assertThrows(ResponseStatusException.class, () -> service.requestRefund(1L, expired.id()));
    }

    @Test
    void onlyOwnerCanDeleteCompletedRefundAndDeletionPersists() {
        var order = service.pay(1L, service.create(1L, room).id());
        assertThrows(ResponseStatusException.class, () -> service.deleteRefund(1L, order.id()));
        service.requestRefund(1L, order.id());
        assertThrows(ResponseStatusException.class, () -> service.deleteRefund(1L, order.id()));
        service.refund(1L, order.id(), "行程有变");
        assertThrows(ResponseStatusException.class, () -> service.deleteRefund(2L, order.id()));
        assertEquals(1, service.listRefunds(1L).size());
        service.deleteRefund(1L, order.id());
        service.deleteRefund(1L, order.id());
        assertTrue(new StayOrderService(jdbc, new TestInventoryPort(jdbc, 10)).listRefunds(1L).isEmpty());
        assertEquals("REFUNDED", service.get(1L, order.id()).status());
        assertNotNull(service.get(1L, order.id()).refundTime());
    }

    @Test
    void allOrdersIncludeExpiredAndHiddenRefundHistoryButOnlyForOwner() {
        var expired = service.pay(1L, service.create(1L, room).id());
        jdbc.update("UPDATE stay_order SET check_out_time = ? WHERE id = ?",
                java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Singapore")).minusSeconds(1), expired.id());
        var refunded = service.pay(1L, service.create(1L, room).id());
        service.requestRefund(1L, refunded.id()); service.refund(1L, refunded.id(), "行程有变");
        service.deleteRefund(1L, refunded.id());
        assertEquals(2, service.listAll(1L).size());
        assertTrue(service.listAll(2L).isEmpty());
        assertEquals("COMPLETED", service.get(1L, expired.id()).status());
        assertTrue(service.listRefunds(1L).isEmpty());
        assertEquals("REFUNDED", service.get(1L, refunded.id()).status());
    }

    @Test
    void onlyOwnerCanCancelUnpaidOrdersAndCancelledOrdersCannotBePaid() {
        var pending = service.create(1L, room);
        assertThrows(ResponseStatusException.class, () -> service.cancel(2L, pending.id()));
        var cancelled = service.cancel(1L, pending.id());
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.cancelledTime());
        assertEquals(cancelled.cancelledTime(), service.cancel(1L, pending.id()).cancelledTime());
        assertThrows(ResponseStatusException.class, () -> service.pay(1L, pending.id()));
        var paid = service.pay(1L, service.create(1L, room).id());
        assertThrows(ResponseStatusException.class, () -> service.cancel(1L, paid.id()));
    }
}
