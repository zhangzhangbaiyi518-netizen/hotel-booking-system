package com.hotel.hotelbookingsystem;

import com.hotel.hotelbookingsystem.inventory.InventoryLock;
import com.hotel.hotelbookingsystem.service.InventoryService;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventoryLockTest {
    private JdbcTemplate jdbc;
    private RLock lock;
    private InventoryService inventory;
    private TransactionTemplate outerTransaction;

    @BeforeEach
    void setup() throws Exception {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        var client = mock(RedissonClient.class);
        lock = mock(RLock.class);
        when(client.getLock("test:inventory:4")).thenReturn(lock);
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenReturn(true);
        var manager = new DataSourceTransactionManager(dataSource);
        outerTransaction = new TransactionTemplate(manager);
        inventory = new InventoryService(jdbc, 10, new InventoryLock(client, "test:inventory:", 1000), manager);
        inventory.initialize();
        jdbc.update("INSERT INTO room_inventory VALUES (4, 10, 0)");
    }

    @Test
    void commitsInventoryBeforeUnlocking() {
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(1, reserved());
            assertEquals(1, reservations());
            return null;
        }).when(lock).unlock();
        inventory.reserve(1L, UUID.randomUUID().toString(), 4L);
        verify(lock).unlock();
    }

    @Test
    void finishesReservationBranchEvenWhenCallerAlreadyHasLocalTransaction() {
        doAnswer(call -> {
            // The suspended caller is active again, but the inner reservation is durable.
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation", Integer.class));
            return null;
        }).when(lock).unlock();
        outerTransaction.executeWithoutResult(status -> {
            inventory.reserve(1L, UUID.randomUUID().toString(), 4L);
            status.setRollbackOnly();
        });
        assertEquals(1, reserved());
        assertEquals(1, reservations());
    }

    @Test
    void rollsBackFailedReservationBeforeUnlocking() {
        jdbc.update("UPDATE room_inventory SET reserved_count = total_count WHERE room_type_id = 4");
        doAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(0, reservations());
            return null;
        }).when(lock).unlock();
        var error = assertThrows(ResponseStatusException.class,
                () -> inventory.reserve(1L, UUID.randomUUID().toString(), 4L));
        assertEquals(409, error.getStatusCode().value());
        assertEquals(10, reserved());
        verify(lock).unlock();
    }

    @Test
    void lockContentionDoesNotCreateReservationOrChangeStock() throws Exception {
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenReturn(false);
        var error = assertThrows(ResponseStatusException.class,
                () -> inventory.reserve(1L, UUID.randomUUID().toString(), 4L));
        assertEquals(503, error.getStatusCode().value());
        assertEquals(0, reserved());
        assertEquals(0, reservations());
        verify(lock, never()).unlock();
    }

    @Test
    void redisFailureDoesNotBypassLock() throws Exception {
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenThrow(new RedisException("test outage"));
        var error = assertThrows(ResponseStatusException.class,
                () -> inventory.reserve(1L, UUID.randomUUID().toString(), 4L));
        assertEquals(503, error.getStatusCode().value());
        assertEquals(0, reserved());
        assertEquals(0, reservations());
        verify(lock, never()).unlock();
    }

    @Test
    void interruptedWaitPreservesInterruptAndDoesNotUnlockAnotherOwner() throws Exception {
        when(lock.tryLock(1000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException());
        try {
            var error = assertThrows(ResponseStatusException.class,
                    () -> inventory.reserve(1L, UUID.randomUUID().toString(), 4L));
            assertEquals(503, error.getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(lock, never()).unlock();
        } finally { Thread.interrupted(); }
        assertEquals(0, reservations());
    }

    @Test
    void lostOwnershipReportsFailureSoGlobalTransactionCanRollback() {
        doThrow(new IllegalMonitorStateException("test expired owner")).when(lock).unlock();
        var error = assertThrows(ResponseStatusException.class,
                () -> inventory.reserve(1L, UUID.randomUUID().toString(), 4L));
        assertEquals(503, error.getStatusCode().value());
        verify(lock, never()).forceUnlock();
    }

    private int reserved() {
        return jdbc.queryForObject("SELECT reserved_count FROM room_inventory WHERE room_type_id = 4", Integer.class);
    }

    private int reservations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM room_reservation", Integer.class);
    }
}
