package com.hotel.hotelbookingsystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.messaging.OrderOutbox;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderMessagingTest {
    private JdbcTemplate jdbc;
    private StayOrderService orders;
    private TransactionTemplate tx;
    private OrderOutbox outbox;
    private TestInventoryPort inventory;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final RoomSnapshot room = new RoomSnapshot(4L, 2L, "酒店", "地址", "123", "大床房", 2, "", new BigDecimal("328"));

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        inventory = spy(new TestInventoryPort(jdbc, 10));
        outbox = new OrderOutbox(jdbc, json);
        orders = new StayOrderService(jdbc, inventory, outbox, 1800000);
        orders.run(null);
    }

    private LocalDateTime past() { return LocalDateTime.now(ZoneId.of("Asia/Singapore")).minusSeconds(1); }
    private void expire(String id) { jdbc.update("UPDATE stay_order SET payment_deadline=? WHERE id=?", past(), id); }

    @Test
    void creationRecordsNotificationAndTimeoutExactlyOnceForRequestReplay() throws Exception {
        String request = UUID.randomUUID().toString();
        var order = orders.create(1L, room, request);
        assertEquals(order.id(), orders.create(1L, room, request).id());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
        var payload = json.readTree(jdbc.queryForObject("SELECT payload FROM order_outbox WHERE event_type='CREATED'", String.class));
        assertEquals(order.id(), payload.get("orderId").asText());
        assertTrue(payload.get("phone").asText().matches("1[0-9]{10}"));
        assertTrue(payload.get("virtualPhone").asBoolean());
        assertEquals("酒店", payload.at("/stay/hotelName").asText());
        assertEquals("大床房", payload.at("/stay/roomName").asText());
        assertTrue(payload.at("/stay/checkInTime").isNull());
        assertNotNull(order.paymentDeadline());
    }

    @Test
    void transactionRollbackRemovesOrderAndBothPendingMessages() {
        tx.executeWithoutResult(status -> {
            orders.create(1L, room);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
        assertTrue(orders.listAll(1L).isEmpty());
    }

    @Test
    void earlyDeliveryReturnsDeadlineAndPaidOrderRemainsReserved() {
        var order = orders.create(1L, room);
        assertEquals(order.paymentDeadline(), tx.execute(status -> orders.cancelExpired(order.id())));
        orders.pay(1L, order.id());
        expire(order.id());
        assertNull(tx.execute(status -> orders.cancelExpired(order.id())));
        assertEquals("AWAITING_CHECK_IN", orders.get(1L, order.id()).status());
        assertEquals(1, orders.inventory(4L).reserved());
    }

    @Test
    void expiredCancellationAndDuplicateDeliveriesReleaseOnlyOnce() {
        var order = orders.create(1L, room);
        expire(order.id());
        tx.executeWithoutResult(status -> orders.cancelExpired(order.id()));
        tx.executeWithoutResult(status -> orders.cancelExpired(order.id()));
        assertEquals("CANCELLED", orders.get(1L, order.id()).status());
        assertEquals(0, orders.inventory(4L).reserved());
        verify(inventory, times(1)).release(1L, order.id());
    }

    @Test
    void paymentCannotSucceedAfterDeadlineEvenBeforeConsumerRuns() {
        var order = orders.create(1L, room);
        expire(order.id());
        var error = assertThrows(ResponseStatusException.class, () -> tx.execute(status -> orders.pay(1L, order.id())));
        assertEquals(409, error.getStatusCode().value());
        assertEquals("PENDING_PAYMENT", orders.get(1L, order.id()).status());
    }

    @Test
    void inventoryFailureRollsBackTimeoutStatusForRetry() {
        var order = orders.create(1L, room);
        expire(order.id());
        doThrow(new IllegalStateException("test inventory outage")).when(inventory).release(1L, order.id());
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> orders.cancelExpired(order.id())));
        assertEquals("PENDING_PAYMENT", orders.get(1L, order.id()).status());
        assertEquals(1, jdbc.queryForObject("SELECT inventory_held FROM stay_order WHERE id=?", Integer.class, order.id()));
        assertEquals(1, orders.inventory(4L).reserved());
    }

    @Test
    void concurrentPublishersClaimEventOnceAndCanRecoverExpiredLease() throws Exception {
        var order = orders.create(1L, room);
        String id = "created:" + order.id();
        jdbc.update("UPDATE order_outbox SET next_attempt_at=? WHERE id=?", past(), id);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<OrderOutbox.Event> claim = () -> tx.execute(status -> outbox.claim(id));
            int claimed = 0;
            for (var future : pool.invokeAll(List.of(claim, claim))) if (future.get() != null) claimed++;
            assertEquals(1, claimed);
        } finally { pool.shutdownNow(); }
        jdbc.update("UPDATE order_outbox SET lease_until=? WHERE id=?", past(), id);
        var reclaimed = tx.execute(status -> outbox.claim(id));
        assertNotNull(reclaimed);
        outbox.sent(reclaimed);
        assertNull(tx.execute(status -> outbox.claim(id)));
    }

    @Test
    void oldPublisherCannotAcknowledgeAnotherPublishersRecoveredClaim() {
        var order = orders.create(1L, room);
        String id = "created:" + order.id();
        jdbc.update("UPDATE order_outbox SET next_attempt_at=? WHERE id=?", past(), id);
        var original = tx.execute(status -> outbox.claim(id));
        jdbc.update("UPDATE order_outbox SET lease_until=? WHERE id=?", past(), id);
        var replacement = tx.execute(status -> outbox.claim(id));
        outbox.sent(original);
        assertEquals("SENDING", jdbc.queryForObject("SELECT state FROM order_outbox WHERE id=?", String.class, id));
        outbox.sent(replacement);
        assertEquals("SENT", jdbc.queryForObject("SELECT state FROM order_outbox WHERE id=?", String.class, id));
    }
}
