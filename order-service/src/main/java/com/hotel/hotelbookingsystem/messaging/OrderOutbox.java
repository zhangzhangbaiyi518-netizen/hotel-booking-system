package com.hotel.hotelbookingsystem.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hotel.hotelbookingsystem.service.StayOrderService.StayOrder;
import io.seata.core.context.RootContext;
import io.seata.spring.annotation.GlobalLock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OrderOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public OrderOutbox(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public record Event(String id, String orderId, String type, String payload, LocalDateTime deadline, String owner) {}
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Singapore")); }

    public void recordCreated(StayOrder order, Long userId) {
        insert("created:" + order.id(), order.id(), "CREATED", OrderMessages.created(order, userId), null);
        ensureTimeout(order.id(), order.paymentDeadline());
    }

    public void ensureTimeout(String orderId, LocalDateTime deadline) {
        if (deadline == null) return;
        insert("timeout:" + orderId, orderId, "TIMEOUT", new OrderMessages.Timeout("timeout:" + orderId, orderId), deadline);
    }

    private void insert(String id, String orderId, String type, Object body, LocalDateTime deadline) {
        try {
            jdbc.update("""
                    INSERT INTO order_outbox(id,order_id,event_type,payload,deadline_at,global_xid)
                    VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE payload = payload
                    """, id, orderId, type, json.writeValueAsString(body), deadline, RootContext.getXID());
        } catch (JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize order event", exception); }
    }

    public List<String> pending() {
        return jdbc.queryForList("""
                SELECT id FROM order_outbox WHERE next_attempt_at <= ? AND
                (state = 'NEW' OR (state = 'SENDING' AND lease_until < ?))
                ORDER BY created_at,id LIMIT 40
                """, String.class, now(), now());
    }

    // AT exposes phase-one rows before global commit. A current read protected by
    // Seata's global lock prevents publishing a row that is still being committed/undone.
    @GlobalLock(lockRetryInterval = 50, lockRetryTimes = 1)
    @Transactional
    public Event claim(String id) {
        String owner = UUID.randomUUID().toString();
        var rows = jdbc.query("""
                SELECT * FROM order_outbox WHERE id = ? AND next_attempt_at <= ? AND
                (state = 'NEW' OR (state = 'SENDING' AND lease_until < ?)) FOR UPDATE
                """, (rs, row) -> {
                    var deadline = rs.getTimestamp("deadline_at");
                    return new Event(rs.getString("id"), rs.getString("order_id"), rs.getString("event_type"),
                            rs.getString("payload"), deadline == null ? null : deadline.toLocalDateTime(), owner);
                }, id, now(), now());
        if (rows.isEmpty()) return null;
        jdbc.update("UPDATE order_outbox SET state='SENDING',owner=?,lease_until=?,attempts=attempts+1 WHERE id=?",
                owner, now().plusSeconds(30), id);
        return rows.get(0);
    }

    @GlobalLock
    @Transactional
    public void sent(Event event) {
        jdbc.update("UPDATE order_outbox SET state='SENT',sent_at=?,owner=NULL,lease_until=NULL WHERE id=? AND owner=?",
                now(), event.id(), event.owner());
    }

    @GlobalLock
    @Transactional
    public void retry(Event event) {
        jdbc.update("UPDATE order_outbox SET state='NEW',next_attempt_at=?,owner=NULL,lease_until=NULL WHERE id=? AND owner=?",
                now().plusSeconds(5), event.id(), event.owner());
    }
}
