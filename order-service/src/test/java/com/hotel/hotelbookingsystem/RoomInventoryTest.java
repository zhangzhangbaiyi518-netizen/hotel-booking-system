package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import com.hotel.hotelbookingsystem.service.StayOrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class RoomInventoryTest {
    private JdbcTemplate jdbc;
    private StayOrderService orders;
    private TransactionTemplate tx;
    private final RoomSnapshot room = new RoomSnapshot(4L, 2L, "酒店", "地址", "123", "大床房", 2, "", new BigDecimal("328"));

    @BeforeEach
    void setup() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        orders = new StayOrderService(jdbc, new TestInventoryPort(jdbc, 3));
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        orders.run(null);
    }

    private <T> List<T> parallel(int count, Callable<T> operation) throws Exception {
        var pool = Executors.newFixedThreadPool(12);
        try {
            var tasks = new ArrayList<Callable<T>>();
            for (int i = 0; i < count; i++) tasks.add(operation);
            var result = new ArrayList<T>();
            for (var future : pool.invokeAll(tasks)) result.add(future.get(20, TimeUnit.SECONDS));
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sixtyConcurrentOrdersCannotOversellThreeRooms() throws Exception {
        var results = parallel(60, () -> {
            try {
                orders.create(1L, room, UUID.randomUUID().toString());
                return 201;
            } catch (ResponseStatusException e) {
                return e.getStatusCode().value();
            }
        });
        assertEquals(3, results.stream().filter(status -> status == 201).count());
        assertEquals(57, results.stream().filter(status -> status == 409).count());
        assertEquals(3, orders.listAll(1L).size());
        assertEquals(3, orders.inventory(4L).reserved());
        assertEquals(0, orders.inventory(4L).available());
    }

    @Test
    void simultaneousRetriesCreateOneOrderAndReserveOneRoom() throws Exception {
        String request = UUID.randomUUID().toString();
        var results = parallel(24, () -> orders.create(1L, room, request).id());
        assertEquals(1, results.stream().distinct().count());
        assertEquals(1, orders.listAll(1L).size());
        assertEquals(2, orders.inventory(4L).available());
    }

    @Test
    void rollbackRestoresOrderAndInventoryTogether() {
        orders.inventory(4L);
        tx.executeWithoutResult(status -> {
            orders.create(1L, room, UUID.randomUUID().toString());
            status.setRollbackOnly();
        });
        assertTrue(orders.listAll(1L).isEmpty());
        assertEquals(3, orders.inventory(4L).available());
    }

    @Test
    void cancellationAndRefundRetriesReleaseExactlyOnce() throws Exception {
        var cancelled = orders.create(1L, room);
        parallel(16, () -> tx.execute(status -> orders.cancel(1L, cancelled.id())));
        assertEquals(3, orders.inventory(4L).available());
        var paid = orders.pay(1L, orders.create(1L, room).id());
        orders.requestRefund(1L, paid.id());
        assertEquals(2, orders.inventory(4L).available());
        parallel(16, () -> tx.execute(status -> orders.refund(1L, paid.id(), "行程有变")));
        assertEquals(3, orders.inventory(4L).available());
        assertEquals(0, orders.inventory(4L).reserved());
    }

    @Test
    void paymentAndCancellationRaceKeepInventoryConsistent() throws Exception {
        var order = orders.create(1L, room);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> pay = () -> {
                try { tx.execute(status -> orders.pay(1L, order.id())); }
                catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); }
                return null;
            };
            Callable<Void> cancel = () -> {
                try { tx.execute(status -> orders.cancel(1L, order.id())); }
                catch (ResponseStatusException e) { assertEquals(409, e.getStatusCode().value()); }
                return null;
            };
            for (var future : pool.invokeAll(List.of(pay, cancel))) future.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        var state = orders.get(1L, order.id()).status();
        assertEquals("CANCELLED".equals(state) ? 0 : 1, orders.inventory(4L).reserved());
    }

    @Test
    void completedStaysReleaseOnceAndRequestReplayDoesNotReserveAgain() {
        String request = UUID.randomUUID().toString();
        var order = orders.pay(1L, orders.create(1L, room, request).id());
        jdbc.update("UPDATE stay_order SET check_out_time = ? WHERE id = ?",
                LocalDateTime.now(ZoneId.of("Asia/Singapore")).minusSeconds(1), order.id());
        tx.executeWithoutResult(status -> orders.releaseCompletedStays());
        tx.executeWithoutResult(status -> orders.releaseCompletedStays());
        assertEquals(3, orders.inventory(4L).available());
        assertEquals(order.id(), orders.create(1L, room, request).id());
        assertEquals(3, orders.inventory(4L).available());
    }

    @Test
    void legacyOverbookedDataBlocksNewOrdersUntilEnoughRoomsAreReleased() {
        for (int i = 0; i < 4; i++) {
            jdbc.update("""
                    INSERT INTO stay_order (id,user_id,hotel_id,room_type_id,hotel_name,room_name,amount)
                    VALUES (?,1,2,4,'旧酒店','旧房型',328)
                    """, "legacy-" + i);
        }
        orders.run(null);
        orders.migrateLegacyInventory();
        orders.migrateLegacyInventory();
        assertEquals(4, orders.inventory(4L).reserved());
        assertEquals(0, orders.inventory(4L).available());
        assertThrows(ResponseStatusException.class, () -> orders.create(1L, room));
        tx.execute(status -> orders.cancel(1L, "legacy-0"));
        assertEquals(0, orders.inventory(4L).available());
        tx.execute(status -> orders.cancel(1L, "legacy-1"));
        assertEquals(1, orders.inventory(4L).available());
    }

    @Test
    void requestIdCannotBeReusedForAnotherRoom() {
        String request = UUID.randomUUID().toString();
        orders.create(1L, room, request);
        var other = new RoomSnapshot(5L, 2L, "酒店", "地址", "123", "双床房", 2, "", new BigDecimal("200"));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> orders.create(1L, other, request)).getStatusCode().value());
        assertEquals(1, orders.listAll(1L).size());
        assertEquals(1, orders.inventory(4L).reserved());
    }

    @Test
    void cancelledTransactionRollbackKeepsTheReservation() {
        var order = orders.create(1L, room);
        tx.executeWithoutResult(status -> {
            orders.cancel(1L, order.id());
            status.setRollbackOnly();
        });
        assertEquals("PENDING_PAYMENT", orders.get(1L, order.id()).status());
        assertEquals(1, orders.inventory(4L).reserved());
    }
}
