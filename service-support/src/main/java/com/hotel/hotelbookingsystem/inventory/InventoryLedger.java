package com.hotel.hotelbookingsystem.inventory;

import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;

/** SQL operations called inside the inventory service's local transaction. */
public class InventoryLedger {
    private final JdbcTemplate jdbc;
    private final int initialStock;

    public InventoryLedger(JdbcTemplate jdbc, int initialStock) {
        if (initialStock < 0) throw new IllegalArgumentException("Initial stock must not be negative");
        this.jdbc = jdbc;
        this.initialStock = initialStock;
    }

    public void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("db/room-inventory.sql"))
                .execute(java.util.Objects.requireNonNull(jdbc.getDataSource()));
    }

    private void ensureInventory(Long roomTypeId) {
        jdbc.update("""
                INSERT INTO room_inventory (room_type_id, total_count, reserved_count) VALUES (?, ?, 0)
                ON DUPLICATE KEY UPDATE total_count = total_count
                """, roomTypeId, initialStock);
    }

    private void lockInventoryBeforeWrite(Long roomTypeId, int total) {
        // Check the shared stock row's Seata global lock before acquiring locks
        // for a new reservation. This also gives all stock writers the same SQL
        // lock order and avoids partially acquired AT locks on branch conflicts.
        var existing = jdbc.queryForList("SELECT room_type_id FROM room_inventory WHERE room_type_id = ? FOR UPDATE",
                Long.class, roomTypeId);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO room_inventory(room_type_id,total_count,reserved_count) VALUES(?,?,0) "
                    + "ON DUPLICATE KEY UPDATE total_count=total_count", roomTypeId, total);
        }
    }

    public RoomInventory inventory(Long roomTypeId) {
        ensureInventory(roomTypeId);
        return jdbc.queryForObject(
                "SELECT total_count, reserved_count FROM room_inventory WHERE room_type_id = ? FOR UPDATE",
                (rs, row) -> new RoomInventory(roomTypeId, rs.getInt("total_count"), rs.getInt("reserved_count"),
                        Math.max(0, rs.getInt("total_count") - rs.getInt("reserved_count"))), roomTypeId);
    }

    public void reserve(Long userId, String orderId, Long roomTypeId) {
        lockInventoryBeforeWrite(roomTypeId, initialStock);
        String token = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO room_reservation (order_id,user_id,room_type_id,state,token) VALUES (?,?,?,'HELD',?)
                ON DUPLICATE KEY UPDATE token = token
                """, orderId, userId, roomTypeId, token);
        var record = jdbc.queryForMap("SELECT * FROM room_reservation WHERE order_id = ? FOR UPDATE", orderId);
        if (((Number) record.get("user_id")).longValue() != userId
                || ((Number) record.get("room_type_id")).longValue() != roomTypeId) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "订单库存归属不一致");
        }
        if (!"HELD".equals(record.get("state"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单库存已释放");
        }
        if (!token.equals(record.get("token"))) return;
        ensureInventory(roomTypeId);
        int changed = jdbc.update("""
                UPDATE room_inventory SET reserved_count = reserved_count + 1
                WHERE room_type_id = ? AND reserved_count < total_count
                """, roomTypeId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "该房型库存不足，请选择其他房型");
    }

    public void release(Long userId, String orderId) {
        var rooms = jdbc.queryForList("SELECT room_type_id FROM room_reservation WHERE order_id = ?", Long.class, orderId);
        if (rooms.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单库存记录尚未迁移");
        lockInventoryBeforeWrite(rooms.get(0), initialStock);
        var records = jdbc.queryForList("SELECT * FROM room_reservation WHERE order_id = ? FOR UPDATE", orderId);
        if (records.isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT, "订单库存记录尚未迁移");
        var record = records.get(0);
        if (((Number) record.get("user_id")).longValue() != userId) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "订单库存不存在");
        }
        if ("RELEASED".equals(record.get("state"))) return;
        jdbc.update("UPDATE room_reservation SET state = 'RELEASED' WHERE order_id = ?", orderId);
        int changed = jdbc.update("""
                UPDATE room_inventory SET reserved_count = reserved_count - 1
                WHERE room_type_id = ? AND reserved_count > 0
                """, record.get("room_type_id"));
        if (changed != 1) throw new IllegalStateException("Inventory counter is inconsistent");
    }

    public void importReservation(Long userId, String orderId, Long roomTypeId, Integer total) {
        int capacity = total == null ? initialStock : total;
        if (capacity < 0) throw new IllegalArgumentException("Legacy stock must not be negative");
        var imported = jdbc.queryForList("SELECT user_id,room_type_id FROM room_reservation WHERE order_id=?", orderId);
        if (!imported.isEmpty()) {
            var row = imported.get(0);
            if (((Number) row.get("user_id")).longValue() != userId
                    || ((Number) row.get("room_type_id")).longValue() != roomTypeId) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "迁移库存归属不一致");
            }
            return;
        }
        lockInventoryBeforeWrite(roomTypeId, capacity);
        jdbc.update("""
                INSERT INTO room_reservation (order_id,user_id,room_type_id,state,token) VALUES (?,?,?,'HELD',?)
                ON DUPLICATE KEY UPDATE token = token
                """, orderId, userId, roomTypeId, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO room_inventory (room_type_id,total_count,reserved_count) VALUES (?,?,0)
                ON DUPLICATE KEY UPDATE total_count = total_count
                """, roomTypeId, capacity);
        Integer held = jdbc.queryForObject(
                "SELECT COUNT(*) FROM room_reservation WHERE room_type_id = ? AND state = 'HELD'", Integer.class, roomTypeId);
        jdbc.update("UPDATE room_inventory SET reserved_count = GREATEST(reserved_count, ?) WHERE room_type_id = ?", held, roomTypeId);
    }
}
