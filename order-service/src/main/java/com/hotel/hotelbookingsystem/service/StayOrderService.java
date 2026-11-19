package com.hotel.hotelbookingsystem.service;

import com.hotel.hotelbookingsystem.http.RoomSnapshot;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Autowired;
import com.hotel.hotelbookingsystem.inventory.InventoryPort;
import com.hotel.hotelbookingsystem.inventory.RoomInventory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@org.springframework.core.annotation.Order(0)
public class StayOrderService implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final InventoryPort inventory;
    private final com.hotel.hotelbookingsystem.messaging.OrderOutbox outbox;
    private final long paymentTimeoutMs;
    private boolean legacyInventoryExists;
    private final TransactionTemplate transactions;
    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Singapore"));
    }

    private static String randomRoomNumber() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100, 1000));
    }

    public StayOrderService(JdbcTemplate jdbc, InventoryPort inventory) {
        this(jdbc, inventory, new com.hotel.hotelbookingsystem.messaging.OrderOutbox(jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()), 1800000);
    }

    @Autowired
    public StayOrderService(JdbcTemplate jdbc, InventoryPort inventory,
            com.hotel.hotelbookingsystem.messaging.OrderOutbox outbox,
            @org.springframework.beans.factory.annotation.Value("${hotel.orders.payment-timeout-ms:1800000}") long paymentTimeoutMs) {
        if (paymentTimeoutMs < 1000) throw new IllegalArgumentException("Payment timeout must be at least 1000 ms");
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.outbox = outbox;
        this.paymentTimeoutMs = paymentTimeoutMs;
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(
                java.util.Objects.requireNonNull(jdbc.getDataSource())));
    }

    @Override
    public void run(ApplicationArguments args) {
        // Add only the order table; existing hotel/user tables and data are untouched.
        new ResourceDatabasePopulator(new ClassPathResource("db/stay-order.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        new ResourceDatabasePopulator(new ClassPathResource("db/undo-log.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        new ResourceDatabasePopulator(new ClassPathResource("db/order-outbox.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        legacyInventoryExists = Boolean.TRUE.equals(jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    try (var tables = connection.getMetaData().getTables(connection.getCatalog(), null, "room_inventory", null)) {
                        return tables.next();
                    }
                }));
        ensureColumn("request_id", "VARCHAR(36) NULL");
        ensureColumn("inventory_held", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn("payment_deadline", "DATETIME(3) NULL");
        ensureColumn("check_in_time", "DATETIME NULL");
        ensureColumn("check_out_time", "DATETIME NULL");
        ensureColumn("room_number", "VARCHAR(3) NULL");
        ensureColumn("refund_request_time", "DATETIME NULL");
        ensureColumn("refund_time", "DATETIME NULL");
        ensureColumn("refund_reason", "VARCHAR(100) NULL");
        ensureColumn("refund_deleted", "TINYINT NOT NULL DEFAULT 0");
        ensureColumn("cancelled_time", "DATETIME NULL");
        ensureIndex("uk_stay_order_request", "user_id, request_id", true);
        ensureIndex("idx_stay_order_inventory", "inventory_held, status, check_out_time, room_type_id", false);
        ensureIndex("idx_stay_order_deadline", "status, payment_deadline", false);
        // Existing pending orders receive one payment window when this feature is
        // first installed; later restarts preserve the persisted deadline.
        jdbc.query("SELECT id FROM stay_order WHERE status='PENDING_PAYMENT' AND payment_deadline IS NULL",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> jdbc.update(
                        "UPDATE stay_order SET payment_deadline=? WHERE id=? AND payment_deadline IS NULL",
                        now().plus(java.time.Duration.ofMillis(paymentTimeoutMs)), rs.getString("id")));
        // Upgrade earlier paid orders without changing their payment timestamps.
        jdbc.query("SELECT id, paid_time FROM stay_order WHERE status = 'AWAITING_CHECK_IN' " +
                "AND (check_in_time IS NULL OR check_out_time IS NULL OR room_number IS NULL)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    LocalDateTime paid = time(rs, "paid_time");
                    LocalDateTime checkIn = (paid == null ? now() : paid).plusHours(1);
                    jdbc.update("UPDATE stay_order SET check_in_time = COALESCE(check_in_time, ?), " +
                            "check_out_time = COALESCE(check_out_time, ?), room_number = COALESCE(room_number, ?) " +
                            "WHERE id = ?", checkIn, checkIn.plusDays(1), randomRoomNumber(), rs.getString("id"));
                });
    }

    private void ensureIndex(String name, String columns, boolean unique) {
        boolean exists = Boolean.TRUE.equals(jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    try (var indexes = connection.getMetaData().getIndexInfo(
                            connection.getCatalog(), null, "stay_order", false, false)) {
                        while (indexes.next()) {
                            if (name.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
                        }
                        return false;
                    }
                }));
        if (!exists) jdbc.execute("CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + name + " ON stay_order (" + columns + ")");
    }

    private record LegacyHold(String id, Long userId, Long roomTypeId, String status, LocalDateTime deadline) {}

    public void migrateLegacyInventory() {
        transactions.executeWithoutResult(status -> {
            var legacy = jdbc.query("""
                    SELECT id,user_id,room_type_id,status,payment_deadline FROM stay_order WHERE inventory_held = 1 OR
                    status IN ('PENDING_PAYMENT','REFUND_REQUESTED') OR
                    (status = 'AWAITING_CHECK_IN' AND check_out_time > ?)
                    ORDER BY room_type_id,id
                    """, (rs, row) -> new LegacyHold(rs.getString("id"),rs.getLong("user_id"),rs.getLong("room_type_id"),
                    rs.getString("status"), time(rs, "payment_deadline")), now());
            for (LegacyHold hold : legacy) {
                Integer total = null;
                if (legacyInventoryExists) {
                    var totals = jdbc.queryForList("SELECT total_count FROM room_inventory WHERE room_type_id = ?", Integer.class, hold.roomTypeId());
                    if (!totals.isEmpty()) total = totals.get(0);
                }
                inventory.importHeld(hold.userId(), hold.id(), hold.roomTypeId(), total);
                jdbc.update("UPDATE stay_order SET inventory_held = 1 WHERE id = ?", hold.id());
                if ("PENDING_PAYMENT".equals(hold.status())) outbox.ensureTimeout(hold.id(), hold.deadline());
            }
        });
    }

    @Transactional
    public RoomInventory inventory(Long roomTypeId) {
        releaseCompletedStays(roomTypeId);
        return inventory.inventory(roomTypeId);
    }

    @Transactional
    public void releaseCompletedStays() {
        var completed = jdbc.query("""
                SELECT * FROM stay_order WHERE inventory_held = 1 AND status = 'AWAITING_CHECK_IN'
                AND check_out_time <= ? ORDER BY room_type_id, id LIMIT 200
                """, ORDER_MAPPER, now());
        for (StayOrder order : completed) releaseInventory(order);
    }

    private void releaseCompletedStays(Long roomTypeId) {
        var completed = jdbc.query("""
                SELECT * FROM stay_order WHERE room_type_id = ? AND inventory_held = 1
                AND status = 'AWAITING_CHECK_IN' AND check_out_time <= ? ORDER BY id
                """, ORDER_MAPPER, roomTypeId, now());
        for (StayOrder order : completed) releaseInventory(order);
    }

    private void releaseInventory(StayOrder order) {
        if (jdbc.update("""
                UPDATE stay_order SET inventory_held = 0 WHERE id = ? AND inventory_held = 1 AND
                (status IN ('CANCELLED', 'REFUNDED')
                 OR (status = 'AWAITING_CHECK_IN' AND check_out_time <= ?))
                """, order.id(), now()) == 1) {
            Long userId = jdbc.queryForObject("SELECT user_id FROM stay_order WHERE id = ?", Long.class, order.id());
            inventory.release(userId, order.id());
        }
    }

    private void ensureColumn(String name, String definition) {
        boolean exists = Boolean.TRUE.equals(jdbc.execute(
                (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                    try (var columns = connection.getMetaData().getColumns(
                            connection.getCatalog(), null, "stay_order", name)) {
                        return columns.next();
                    }
                }));
        if (!exists) jdbc.execute("ALTER TABLE stay_order ADD COLUMN " + name + " " + definition);
    }

    public record StayOrder(String id, Long hotelId, Long roomTypeId, String hotelName,
                            String hotelAddress, String hotelPhone, String roomName,
                            Integer capacity, String description, BigDecimal amount,
                            String status, LocalDateTime createTime, LocalDateTime paymentDeadline, LocalDateTime paidTime,
                            LocalDateTime checkInTime, LocalDateTime checkOutTime, String roomNumber,
                            LocalDateTime refundRequestTime, LocalDateTime refundTime, String refundReason,
                            LocalDateTime cancelledTime) {}

    private static String displayStatus(ResultSet rs) throws SQLException {
        String status = rs.getString("status");
        LocalDateTime end = time(rs, "check_out_time");
        return "AWAITING_CHECK_IN".equals(status) && end != null && !end.isAfter(now())
                ? "COMPLETED" : status;
    }

    private static LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private static final RowMapper<StayOrder> ORDER_MAPPER = (rs, row) -> new StayOrder(
            rs.getString("id"), rs.getLong("hotel_id"), rs.getLong("room_type_id"),
            rs.getString("hotel_name"), rs.getString("hotel_address"), rs.getString("hotel_phone"),
            rs.getString("room_name"), rs.getObject("capacity", Integer.class), rs.getString("description"),
            rs.getBigDecimal("amount"), displayStatus(rs), time(rs, "create_time"), time(rs, "payment_deadline"), time(rs, "paid_time"),
            time(rs, "check_in_time"), time(rs, "check_out_time"), rs.getString("room_number"),
            time(rs, "refund_request_time"), time(rs, "refund_time"), rs.getString("refund_reason"), time(rs, "cancelled_time"));

    @Transactional
    public StayOrder create(Long userId, RoomSnapshot room) {
        return create(userId, room, null);
    }

    @Transactional
    public StayOrder create(Long userId, RoomSnapshot room, String requestId) {
        String request = requestId == null ? UUID.randomUUID().toString() : requestId;
        try {
            if (!UUID.fromString(request).toString().equals(request)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "下单请求编号无效");
        }
        return transactions.execute(transaction -> {
            var previous = jdbc.query("SELECT * FROM stay_order WHERE user_id = ? AND request_id = ?",
                    ORDER_MAPPER, userId, request);
            if (!previous.isEmpty()) return replay(previous.get(0), room.roomTypeId());

            BigDecimal price = room.price();
            if (price == null || price.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该房型暂不可购买");
            }
            releaseCompletedStays(room.roomTypeId());
            String id = UUID.randomUUID().toString();
            // Upsert takes an exclusive unique-key lock for both new and replayed requests.
            jdbc.update("""
                    INSERT INTO stay_order
                    (id, user_id, request_id, inventory_held, hotel_id, room_type_id, hotel_name, hotel_address,
                     hotel_phone, room_name, capacity, description, amount, status, payment_deadline)
                    VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_PAYMENT', ?)
                    ON DUPLICATE KEY UPDATE amount = amount
                    """, id, userId, request, room.hotelId(), room.roomTypeId(), room.hotelName(),
                    room.hotelAddress(), room.hotelPhone(), room.roomName(), room.capacity(), room.description(), price,
                    now().plus(java.time.Duration.ofMillis(paymentTimeoutMs)));
            StayOrder stored = jdbc.queryForObject(
                    "SELECT * FROM stay_order WHERE user_id = ? AND request_id = ? FOR UPDATE",
                    ORDER_MAPPER, userId, request);
            if (!id.equals(stored.id())) return replay(stored, room.roomTypeId());
            inventory.reserve(userId, id, room.roomTypeId());
            StayOrder created = get(userId, id);
            outbox.recordCreated(created, userId);
            return created;
        });
    }

    private StayOrder replay(StayOrder order, Long roomTypeId) {
        if (!order.roomTypeId().equals(roomTypeId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请求编号已用于其他房型");
        }
        return order;
    }

    public BigDecimal paidSpending(Long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount), 0)
                FROM stay_order
                WHERE user_id = ? AND paid_time IS NOT NULL AND status <> 'REFUNDED'
                """, BigDecimal.class, userId);
    }

    public StayOrder get(Long userId, String id) {
        var orders = jdbc.query("SELECT * FROM stay_order WHERE id = ? AND user_id = ?",
                ORDER_MAPPER, id, userId);
        if (orders.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在");
        return orders.get(0);
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional
    public StayOrder pay(Long userId, String id) {
        LocalDateTime paid = now();
        LocalDateTime checkIn = paid.plusHours(1);
        // Atomic state transition: repeated payment never creates a second stay/order.
        jdbc.update("""
                UPDATE stay_order SET status = 'AWAITING_CHECK_IN', paid_time = ?,
                check_in_time = ?, check_out_time = ?, room_number = ?
                WHERE id = ? AND user_id = ? AND status = 'PENDING_PAYMENT'
                AND (payment_deadline IS NULL OR payment_deadline > ?)
                """, paid, checkIn, checkIn.plusDays(1), randomRoomNumber(), id, userId, paid);
        StayOrder order = get(userId, id);
        if (!"AWAITING_CHECK_IN".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单已超时或当前状态不允许付款");
        }
        return order;
    }

    public List<StayOrder> listAwaitingCheckIn(Long userId) {
        return jdbc.query("""
                SELECT * FROM stay_order WHERE user_id = ? AND status = 'AWAITING_CHECK_IN'
                AND check_out_time > ?
                ORDER BY paid_time DESC, id
                """, ORDER_MAPPER, userId, now());
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional
    public StayOrder requestRefund(Long userId, String id) {
        LocalDateTime requested = now();
        jdbc.update("""
                UPDATE stay_order SET status = 'REFUND_REQUESTED', refund_request_time = ?
                WHERE id = ? AND user_id = ? AND status = 'AWAITING_CHECK_IN' AND check_out_time > ?
                """, requested, id, userId, requested);
        StayOrder order = get(userId, id);
        if (!List.of("REFUND_REQUESTED", "REFUNDED").contains(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单不能申请退款");
        }
        return order;
    }

    @Transactional
    public StayOrder refund(Long userId, String id, String reason) {
        if (reason == null || !List.of("行程有变", "订错酒店或房型", "重复购买", "价格原因", "其他原因").contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择有效退款原因");
        }
        jdbc.update("""
                UPDATE stay_order SET status = 'REFUNDED', refund_time = ?, refund_reason = ?
                WHERE id = ? AND user_id = ? AND status = 'REFUND_REQUESTED'
                """, now(), reason, id, userId);
        StayOrder order = get(userId, id);
        if (!"REFUNDED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先将订单移入退款模块");
        }
        releaseInventory(order);
        return order;
    }

    public List<StayOrder> listRefunds(Long userId) {
        return jdbc.query("""
                SELECT * FROM stay_order WHERE user_id = ? AND status IN ('REFUND_REQUESTED', 'REFUNDED')
                AND refund_deleted = 0
                ORDER BY refund_request_time DESC, id
                """, ORDER_MAPPER, userId);
    }

    @io.seata.spring.annotation.GlobalLock
    @Transactional
    public void deleteRefund(Long userId, String id) {
        StayOrder order = get(userId, id);
        if (!"REFUNDED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能删除已退款订单");
        }
        jdbc.update("UPDATE stay_order SET refund_deleted = 1 WHERE id = ? AND user_id = ? AND status = 'REFUNDED'",
                id, userId);
    }

    public List<StayOrder> listAll(Long userId) {
        return jdbc.query("SELECT * FROM stay_order WHERE user_id = ? ORDER BY create_time DESC, id",
                ORDER_MAPPER, userId);
    }

    @Transactional
    public StayOrder cancel(Long userId, String id) {
        jdbc.update("UPDATE stay_order SET status = 'CANCELLED', cancelled_time = ? " +
                "WHERE id = ? AND user_id = ? AND status = 'PENDING_PAYMENT'", now(), id, userId);
        StayOrder order = get(userId, id);
        if (!"CANCELLED".equals(order.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只能取消未付款订单");
        }
        releaseInventory(order);
        return order;
    }

    /** Returns a future deadline only when an early delivery needs to be delayed again. */
    @Transactional
    public LocalDateTime cancelExpired(String id) {
        var rows = jdbc.query("SELECT user_id,status,payment_deadline FROM stay_order WHERE id=? FOR UPDATE",
                (rs, row) -> new PendingOrder(rs.getLong("user_id"), rs.getString("status"), time(rs, "payment_deadline")), id);
        if (rows.isEmpty() || !"PENDING_PAYMENT".equals(rows.get(0).status())) return null;
        LocalDateTime deadline = rows.get(0).deadline();
        if (deadline == null) return null;
        if (deadline.isAfter(now())) return deadline;
        Long userId = rows.get(0).userId();
        if (jdbc.update("UPDATE stay_order SET status='CANCELLED',cancelled_time=? "
                + "WHERE id=? AND status='PENDING_PAYMENT' AND payment_deadline<=?", now(), id, now()) == 1) {
            releaseInventory(get(userId, id));
        }
        return null;
    }

    private record PendingOrder(Long userId, String status, LocalDateTime deadline) {}

    public List<String> overdueOrders() {
        return jdbc.queryForList("SELECT id FROM stay_order WHERE status='PENDING_PAYMENT' AND payment_deadline<=? "
                + "ORDER BY payment_deadline,id LIMIT 100", String.class, now().minusSeconds(60));
    }
}
